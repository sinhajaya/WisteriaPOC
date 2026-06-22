package com.wisteria.service.explain.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisteria.model.StyleAttributes;
import com.wisteria.service.explain.Explainer;
import com.wisteria.service.rerank.ReRankingService.ScoredCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Batched match explanations (LLD v2.1 Day 4). ONE Claude (Haiku) call with a
 * forced {@code write_match_explanations} tool produces one explanation per
 * product — ~8× cheaper than per-product calls, consistent tone, single
 * fallback path. A 5 s timeout or any failure returns an empty map; the caller
 * then applies template fallbacks. The quality gate (length / attribute
 * mention) is applied by the caller, which holds the matched attributes.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "wisteria.explain.provider", havingValue = "claude")
public class ClaudeExplainer implements Explainer {

    static final String TOOL = "write_match_explanations";
    private static final long TIMEOUT_SEC = 5;

    private static final String SYSTEM = """
            You are an interior-design assistant. You are given the style attributes of a
            user's inspiration image and a list of matched products with their attributes
            and the attributes they share with the inspiration. For each product, write one
            concise explanation (1–2 sentences) that cites the concrete shared attributes
            (e.g. material, era, finish). Call write_match_explanations with exactly one
            entry per product_id.

            If the payload sets "low_confidence": true, the system found no strong match
            (the inspiration may not be a furniture image at all). In that case, hedge every
            explanation: frame each as a loose/approximate match that merely shares a few
            surface traits, and do NOT claim it captures the same overall character.""";

    private final AnthropicClient client;
    private final String model;
    private final ExecutorService executor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Tool tool = buildTool();

    public ClaudeExplainer(AnthropicClient client,
                           @Value("${anthropic.model.explain}") String model,
                           ExecutorService virtualExecutor) {
        this.client = client;
        this.model = model;
        this.executor = virtualExecutor;
    }

    /** product_id → explanation. Empty map on timeout/failure (caller uses templates). */
    @Override
    public Map<UUID, String> explain(StyleAttributes query, List<ScoredCandidate> products, boolean lowConfidence) {
        if (products.isEmpty()) {
            return Map.of();
        }
        try {
            String payload = buildPayload(query, products, lowConfidence);
            return CompletableFuture
                    .supplyAsync(() -> callClaude(payload), executor)
                    .orTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            log.warn("Batched explanation generation failed, using templates: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<UUID, String> callClaude(String payload) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024)
                .system(SYSTEM)
                .addTool(tool)
                .toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder().name(TOOL).build()))
                .addUserMessage(payload)
                .build();

        Message response = client.messages().create(params);
        for (ContentBlock block : response.content()) {
            if (block.isToolUse()) {
                return parse(block.asToolUse());
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, String> parse(ToolUseBlock toolUse) {
        Map<String, Object> input =
                toolUse._input().convert(new TypeReference<Map<String, Object>>() {});
        Map<UUID, String> out = new LinkedHashMap<>();
        if (input == null) {
            return out;
        }
        Object list = input.get("explanations");
        if (list instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> m) {
                    Object id = m.get("product_id");
                    Object text = m.get("explanation");
                    if (id != null && text != null) {
                        try {
                            out.put(UUID.fromString(id.toString()), text.toString());
                        } catch (IllegalArgumentException ignore) {
                            // skip malformed id
                        }
                    }
                }
            }
        }
        return out;
    }

    private String buildPayload(StyleAttributes query, List<ScoredCandidate> products, boolean lowConfidence) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("query_attributes", query != null ? query.asMap() : Map.of());
            root.put("low_confidence", lowConfidence);
            List<Map<String, Object>> items = new ArrayList<>();
            for (ScoredCandidate sc : products) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("product_id", sc.row().productId().toString());
                p.put("name", sc.row().name());
                p.put("attributes", sc.row().attributes().asMap());
                p.put("matched_attributes", sc.matchedAttributes());
                items.add(p);
            }
            root.put("products", items);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("could not build explanation payload", e);
        }
    }

    private static Tool buildTool() {
        Map<String, Object> item = Map.of(
                "type", "object",
                "properties", Map.of(
                        "product_id", Map.of("type", "string"),
                        "explanation", Map.of("type", "string")),
                "required", List.of("product_id", "explanation"));
        Map<String, Object> properties = Map.of(
                "explanations", Map.of("type", "array", "items", item));

        return Tool.builder()
                .name(TOOL)
                .description("Return one concise why-it-matches explanation per product.")
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .properties(JsonValue.from(properties))
                        .required(List.of("explanations"))
                        .build())
                .build();
    }
}
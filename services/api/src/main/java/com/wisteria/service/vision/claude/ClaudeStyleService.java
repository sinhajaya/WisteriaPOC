package com.wisteria.service.vision.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisteria.model.StyleAttributes;
import com.wisteria.service.vision.StyleExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Claude Vision style extraction via forced tool use (LLD v2.1 Day 2).
 * The {@code extract_style_attributes} tool has closed enum vocabularies and
 * all six fields required, which guarantees valid, score-able JSON output.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "wisteria.vision.provider", havingValue = "claude")
public class ClaudeStyleService implements StyleExtractor {

    static final String TOOL = "extract_style_attributes";

    private static final String SYSTEM = """
            You are an interior-design vision system. Look at the image and call the
            extract_style_attributes tool with the single best-fitting value for each field.
            First identify `category` — the KIND of furniture/object that is the main subject
            (e.g. a sideboard or cabinet is `storage`, a chair or sofa is `seating`, a vase or
            mirror is `decor`, a pillow or throw is `textile`, a plate or serveware piece is
            `tabletop`). Then the style attributes. Choose only from the provided enum vocabularies.""";

    private final AnthropicClient client;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Tool tool = buildTool();

    public ClaudeStyleService(AnthropicClient client,
                              @Value("${anthropic.model.extract}") String model) {
        this.client = client;
        this.model = model;
    }

    /**
     * Extract the six style attributes from a normalised JPEG (Base64).
     * Throws on transport failure or if no tool_use block is returned — the
     * caller (indexer / query path) decides whether to retry or degrade.
     */
    @Override
    public StyleAttributes extract(String jpegBase64) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(512)
                .system(SYSTEM)
                .addTool(tool)
                .toolChoice(ToolChoice.ofTool(ToolChoiceTool.builder().name(TOOL).build()))
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofText(TextBlockParam.builder()
                                .text("Extract the style attributes of this image.").build()),
                        ContentBlockParam.ofImage(ImageBlockParam.builder()
                                .source(ImageBlockParam.Source.ofBase64(Base64ImageSource.builder()
                                        .data(jpegBase64)
                                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                        .build()))
                                .build())))
                .build();

        Message response = client.messages().create(params);
        for (ContentBlock block : response.content()) {
            if (block.isToolUse()) {
                ToolUseBlock toolUse = block.asToolUse();
                Map<String, String> in =
                        toolUse._input().convert(new TypeReference<Map<String, String>>() {});
                if (in == null) {
                    throw new IllegalStateException("Claude tool input was empty");
                }
                String rawJson;
                try {
                    rawJson = mapper.writeValueAsString(in);
                } catch (Exception e) {
                    rawJson = null;
                }
                return new StyleAttributes(
                        in.get("category"),
                        in.get("finish"), in.get("material"), in.get("silhouette"),
                        in.get("era"), in.get("palette"), in.get("mood"), rawJson);
            }
        }
        throw new IllegalStateException("Claude returned no tool_use block for " + TOOL);
    }

    private static Tool buildTool() {
        Map<String, Object> properties = Map.of(
                "category", enumProp("seating", "table", "storage", "bed",
                        "lighting", "rug", "decor", "textile", "tabletop", "other"),
                "finish", enumProp("matte", "gloss", "brushed", "aged", "lacquered", "natural"),
                "material", enumProp("oak", "walnut", "velvet", "brass", "linen", "marble",
                        "rattan", "glass", "leather", "ceramic",
                        "teak", "wood", "iron", "cane", "bone"),
                "silhouette", enumProp("clean-line", "curved", "ornate", "sculptural", "minimal"),
                "era", enumProp("mid-century", "art-deco", "japandi", "contemporary",
                        "industrial", "traditional", "organic-modern", "coastal", "bohemian"),
                "palette", enumProp("warm-neutral", "cool-neutral", "earthy", "monochrome", "bold"),
                "mood", enumProp("cosy", "editorial", "calm", "dramatic", "playful"));

        return Tool.builder()
                .name(TOOL)
                .description("Extract interior-design style attributes visible in the image.")
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .properties(JsonValue.from(properties))
                        .required(StyleAttributes.FIELDS)
                        .build())
                .build();
    }

    private static Map<String, Object> enumProp(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
}
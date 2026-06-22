package com.wisteria.service.explain.template;

import com.wisteria.model.StyleAttributes;
import com.wisteria.service.explain.Explainer;
import com.wisteria.service.rerank.ReRankingService.ScoredCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic match explanations (LLD v3.0 default). A pure function of each
 * product's shared attributes — no model call, no network, never fails. Products
 * with no shared attribute are left out so the caller applies its generic
 * template fallback.
 */
@Service
@ConditionalOnProperty(name = "wisteria.explain.provider", havingValue = "template", matchIfMissing = true)
public class TemplateExplainer implements Explainer {

    private static final Map<String, String> LABELS = Map.of(
            "category", "form",
            "palette", "palette",
            "material", "material",
            "finish", "finish",
            "silhouette", "silhouette",
            "era", "era",
            "mood", "mood");

    @Override
    public Map<UUID, String> explain(StyleAttributes query, List<ScoredCandidate> products, boolean lowConfidence) {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (ScoredCandidate sc : products) {
            String why = build(sc.matchedAttributes(), lowConfidence);
            if (why != null) {
                out.put(sc.row().productId(), why);
            }
        }
        return out;
    }

    private String build(Map<String, String> matched, boolean lowConfidence) {
        if (matched == null || matched.isEmpty()) {
            return null;
        }
        List<String> fragments = new ArrayList<>();
        for (String field : StyleAttributes.FIELDS) {
            String value = matched.get(field);
            if (value != null && !value.isBlank()) {
                fragments.add(value + " " + LABELS.getOrDefault(field, field));
            }
        }
        if (fragments.isEmpty()) {
            return null;
        }
        // Low confidence: the attributes overlap but the re-ranker isn't sure this is
        // a genuine style match (e.g. an out-of-domain image whose attributes were
        // extracted into the furniture vocabulary anyway). Hedge instead of asserting.
        if (lowConfidence) {
            return "One of the closest we found: it shares some traits with your inspiration — "
                    + join(fragments) + " — though the overall look may differ.";
        }
        return "This match echoes your inspiration's " + join(fragments)
                + ", carrying the same overall character you loved in the original.";
    }

    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        String head = String.join(", ", parts.subList(0, parts.size() - 1));
        return head + " and " + parts.get(parts.size() - 1);
    }
}
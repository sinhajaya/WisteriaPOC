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

    // Cap on the product's own distinguishing traits we tack on, so the line stays
    // one readable sentence. Prioritised by StyleAttributes.FIELDS order.
    private static final int MAX_DISTINCT_FRAGMENTS = 2;

    @Override
    public Map<UUID, String> explain(StyleAttributes query, List<ScoredCandidate> products, boolean lowConfidence) {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (ScoredCandidate sc : products) {
            String why = build(sc, lowConfidence);
            if (why != null) {
                out.put(sc.row().productId(), why);
            }
        }
        return out;
    }

    private String build(ScoredCandidate sc, boolean lowConfidence) {
        Map<String, String> matched = sc.matchedAttributes();
        if (matched == null || matched.isEmpty()) {
            return null;
        }
        List<String> shared = fragments(matched);
        if (shared.isEmpty()) {
            return null;
        }

        // The product's OWN attributes that aren't part of the shared set. These are
        // what make each explanation distinct even when several top results share the
        // exact same matched attributes with the query (which is the common case, since
        // the re-ranker surfaces high-overlap items together).
        Map<String, String> own = new LinkedHashMap<>(sc.row().attributes().asMap());
        own.keySet().removeAll(matched.keySet());
        List<String> distinct = fragments(own);
        if (distinct.size() > MAX_DISTINCT_FRAGMENTS) {
            distinct = distinct.subList(0, MAX_DISTINCT_FRAGMENTS);
        }

        // Lead with the product name so no two results read identically, even if both
        // the shared and distinguishing attributes happen to coincide.
        String name = sc.row().name();
        String subject = (name != null && !name.isBlank()) ? "This " + name.trim() : "This piece";

        // Low confidence: the attributes overlap but the re-ranker isn't sure this is
        // a genuine style match (e.g. an out-of-domain image whose attributes were
        // extracted into the furniture vocabulary anyway). Hedge instead of asserting.
        if (lowConfidence) {
            return subject + " is one of the closest we found: it shares your inspiration's "
                    + join(shared)
                    + (distinct.isEmpty()
                        ? ", though the overall look may differ."
                        : ", though its own " + join(distinct) + " set it apart.");
        }
        return subject + " echoes your inspiration's " + join(shared)
                + (distinct.isEmpty()
                    ? ", carrying the same overall character you loved in the original."
                    : ", with its own " + join(distinct) + ".");
    }

    /** "{value} {label}" fragments in canonical field order, skipping blanks. */
    private static List<String> fragments(Map<String, String> attrs) {
        List<String> fragments = new ArrayList<>();
        for (String field : StyleAttributes.FIELDS) {
            String value = attrs.get(field);
            if (value != null && !value.isBlank()) {
                fragments.add(value + " " + LABELS.getOrDefault(field, field));
            }
        }
        return fragments;
    }

    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        String head = String.join(", ", parts.subList(0, parts.size() - 1));
        return head + " and " + parts.get(parts.size() - 1);
    }
}
package com.wisteria.service.explain;

import com.wisteria.model.StyleAttributes;
import com.wisteria.service.rerank.ReRankingService.ScoredCandidate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Explanation seam: produces the "why this matches" note per product. Selected
 * by {@code wisteria.explain.provider} — {@code template} (deterministic, LLD
 * v3.0 default) or {@code claude} (Haiku, LLD v2.1).
 */
public interface Explainer {

    /**
     * product_id → explanation. Missing/blank entries fall back to the caller's template.
     *
     * @param lowConfidence the re-ranker found no confident match (top score under
     *                      threshold, or the top hit isn't visually close — e.g. an
     *                      out-of-domain query). Explanations must then be hedged,
     *                      not asserted as a strong style match.
     */
    Map<UUID, String> explain(StyleAttributes query, List<ScoredCandidate> products, boolean lowConfidence);
}
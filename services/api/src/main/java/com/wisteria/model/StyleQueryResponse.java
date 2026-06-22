package com.wisteria.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Top-level response of POST /api/v1/style/query.
 * Field names are snake_case on the wire (API contract, LLD v2.1).
 */
public record StyleQueryResponse(
        @JsonProperty("query_id")         UUID queryId,
        @JsonProperty("cache_hit")        boolean cacheHit,
        @JsonProperty("latency_ms")       long latencyMs,
        @JsonProperty("low_confidence")   boolean lowConfidence,
        // True when the image isn't furniture at all (zero-shot CLIP gate). The UI
        // shows a distinct "not a furniture image" state — NOT the low-confidence
        // "closest matches" banner — and results is empty.
        @JsonProperty("out_of_domain")    boolean outOfDomain,
        // True when the VLM was unavailable and the query fell back to embedding-only
        // (CLIP) search. low_confidence is also true in this case, but vlm_degraded
        // distinguishes a transient system fault from a genuine weak-match verdict.
        @JsonProperty("vlm_degraded")     boolean vlmDegraded,
        @JsonProperty("query_attributes") QueryAttributes queryAttributes,
        @JsonProperty("results")          List<ResultItem> results
) {

    /** Style DNA extracted from the uploaded image by Claude Vision. */
    public record QueryAttributes(
            String category,    // seating | table | storage | bed | lighting | rug | decor | other
            String finish,      // matte | gloss | brushed | aged | lacquered | natural
            String material,    // oak | walnut | ... | teak | wood | iron | cane | bone
            String silhouette,  // clean-line | curved | ornate | sculptural | minimal
            String era,         // mid-century | ... | organic-modern | coastal | bohemian
            String palette,     // warm-neutral | cool-neutral | earthy | monochrome | bold
            String mood         // cosy | editorial | calm | dramatic | playful
    ) {}

    /** One matched product in the ranked result list. */
    public record ResultItem(
            @JsonProperty("product_id")         UUID productId,
            @JsonProperty("name")               String name,
            @JsonProperty("image_url")          String imageUrl,
            @JsonProperty("similarity_score")   int similarityScore,   // 0–100
            @JsonProperty("matched_attributes") Map<String, String> matchedAttributes,
            @JsonProperty("why_matches")        String whyMatches      // null if explanation degraded
    ) {}
}

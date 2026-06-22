package com.wisteria.service.rerank;

import com.wisteria.model.StyleAttributes;
import com.wisteria.repository.CandidateRow;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-ranking with an explicit preference order (LLD v2.1 + matching refinement):
 *   1. kind of furniture (category)  — PRIMARY: same-category items always rank first
 *   2. colour (palette)
 *   3. material
 *   4. everything else (finish, silhouette, era, mood)
 *
 * Ordering is lexicographic on category-match, then a bounded blended score:
 * <pre>
 *   cosineSim    = 1 - cosineDistance                         (visual, [0,1])
 *   attrOverlap  = Σ weight(field) for each exactly-matching field
 *   finalScore   = 0.30·cosineSim + 0.70·attrOverlap          ∈ [0,1]
 *   apiScore     = round(finalScore · 100)
 * </pre>
 * The category weight (0.40) exceeds the entire visual contribution, and a
 * category match is also the primary sort key, so a sideboard query ranks all
 * storage items above chairs regardless of visual similarity.
 *
 * <p><b>Visual floor.</b> The VLM uses a closed furniture vocabulary with no
 * "not furniture" option, so an out-of-domain query (e.g. an auto-rickshaw)
 * still gets confident furniture attributes and can match the catalog on the
 * high-weight category/palette/material fields — inflating {@code finalScore}
 * above the low-confidence threshold even though it looks nothing alike. Since
 * attribute overlap carries 70% of the score, the numeric blend alone can't
 * catch this. As a backstop, a top result whose raw visual similarity is below
 * {@link #VISUAL_FLOOR} is forced to {@code lowConfidence} regardless of score:
 * a genuine style match should at least look somewhat similar.
 */
@Service
public class ReRankingService {

    private static final double ALPHA = 0.30;          // visual weight
    private static final double BETA = 0.70;           // attribute weight
    private static final double LOW_CONFIDENCE = 0.45;
    // Min raw CLIP cosine similarity for the top hit to count as a confident
    // match. Below this, attribute overlap is likely a closed-vocabulary
    // hallucination on an out-of-domain image — flag low-confidence.
    private static final double VISUAL_FLOOR = 0.50;

    /** Field weights (sum to 1.0), encoding the preference order. */
    private static final Map<String, Double> WEIGHTS = Map.of(
            "category", 0.40,
            "palette", 0.25,    // colour
            "material", 0.20,
            "finish", 0.0375,
            "silhouette", 0.0375,
            "era", 0.0375,
            "mood", 0.0375);

    public RankingOutcome rank(StyleAttributes query, List<CandidateRow> candidates, int topK) {
        Map<String, String> queryMap = query != null ? query.asMap() : Map.of();

        List<ScoredCandidate> scored = candidates.stream()
                .map(c -> score(queryMap, c))
                // PRIMARY: same furniture category first; then by blended score.
                .sorted(Comparator.comparing(ScoredCandidate::categoryMatch).reversed()
                        .thenComparing(Comparator.comparingDouble(ScoredCandidate::finalScore).reversed()))
                .limit(Math.max(1, topK))
                .toList();

        double best = scored.isEmpty() ? 0.0 : scored.get(0).finalScore();
        int topScore = scored.isEmpty() ? 0 : scored.get(0).apiScore();
        // Top hit's raw visual similarity — the backstop for out-of-domain queries
        // whose hallucinated attributes inflate the blended score (see class doc).
        double topVisualSim = scored.isEmpty()
                ? 0.0 : clamp01(1.0 - scored.get(0).row().cosineDistance());
        boolean lowConfidence = scored.isEmpty()
                || best < LOW_CONFIDENCE
                || topVisualSim < VISUAL_FLOOR;
        return new RankingOutcome(scored, lowConfidence, topScore);
    }

    private ScoredCandidate score(Map<String, String> queryMap, CandidateRow c) {
        double cosineSim = clamp01(1.0 - c.cosineDistance());
        Map<String, String> matched = matchedAttributes(queryMap, c.attributes().asMap());
        double overlap = matched.keySet().stream()
                .mapToDouble(k -> WEIGHTS.getOrDefault(k, 0.0))
                .sum();
        double finalScore = ALPHA * cosineSim + BETA * overlap;
        int apiScore = (int) Math.round(finalScore * 100);
        boolean categoryMatch = matched.containsKey("category");
        return new ScoredCandidate(c, apiScore, finalScore, categoryMatch, matched);
    }

    /** Exact (case-insensitive) matches across the expressed fields. */
    private static Map<String, String> matchedAttributes(Map<String, String> query,
                                                         Map<String, String> candidate) {
        Map<String, String> matched = new LinkedHashMap<>();
        for (String field : StyleAttributes.FIELDS) {
            String q = query.get(field);
            String c = candidate.get(field);
            if (q != null && c != null && q.equalsIgnoreCase(c)) {
                matched.put(field, c);
            }
        }
        return matched;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    /** One scored candidate. {@code categoryMatch} is the primary ranking key. */
    public record ScoredCandidate(CandidateRow row, int apiScore, double finalScore,
                                  boolean categoryMatch, Map<String, String> matchedAttributes) {}

    /** Re-ranking result: the top-K scored candidates plus aggregate flags. */
    public record RankingOutcome(List<ScoredCandidate> ranked, boolean lowConfidence, int topScore) {}
}

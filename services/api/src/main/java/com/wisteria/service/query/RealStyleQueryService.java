package com.wisteria.service.query;

import com.wisteria.exception.WisteriaExceptions.EmbeddingUnavailableException;
import com.wisteria.exception.WisteriaExceptions.ModelVersionMismatchException;
import com.wisteria.exception.WisteriaExceptions.UnsupportedImageException;
import com.wisteria.model.StyleAttributes;
import com.wisteria.model.StyleQueryResponse;
import com.wisteria.model.StyleQueryResponse.QueryAttributes;
import com.wisteria.model.StyleQueryResponse.ResultItem;
import com.wisteria.repository.CandidateRow;
import com.wisteria.repository.ProductRepository;
import com.wisteria.repository.QueryLogRepository;
import com.wisteria.service.ImageNormalizer;
import com.wisteria.service.NormalizedImage;
import com.wisteria.service.QueryReadiness;
import com.wisteria.service.StyleQueryService;
import com.wisteria.service.cache.CacheService;
import com.wisteria.service.clip.ClipClient;
import com.wisteria.service.explain.Explainer;
import com.wisteria.service.rerank.ReRankingService;
import com.wisteria.service.rerank.ReRankingService.RankingOutcome;
import com.wisteria.service.rerank.ReRankingService.ScoredCandidate;
import com.wisteria.service.vision.StyleExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Online query pipeline (LLD v2.1 Day 3–4).
 *
 * normalize → pHash cache probe → parallel{CLIP, vision} (bounded) → ANN top-50
 * → bounded re-rank → batched explanations (+ template fallback) → assemble
 * → cache write + query log.
 *
 * Degradation rules: vision-extraction failure ⇒ embedding-only search (no
 * attribute boost, template explanations); CLIP failure ⇒ 503 (embeddings are
 * the backbone); Redis failure ⇒ uncached run.
 */
@Slf4j
@Service
public class RealStyleQueryService implements StyleQueryService {

    private static final int CANDIDATE_POOL = 50;
    private static final long DEADLINE_MS = 15_000;
    private static final int MIN_EXPLANATION_WORDS = 15;

    private final ImageNormalizer normalizer;
    private final ClipClient clip;
    private final StyleExtractor extractor;
    private final ProductRepository repository;
    private final ReRankingService reRanker;
    private final Explainer explainer;
    private final CacheService cache;
    private final QueryLogRepository queryLog;
    private final QueryReadiness readiness;
    private final ExecutorService executor;
    private final boolean gateEnabled;
    private final double furnitureThreshold;

    public RealStyleQueryService(ImageNormalizer normalizer, ClipClient clip,
                                 StyleExtractor extractor, ProductRepository repository,
                                 ReRankingService reRanker, Explainer explainer,
                                 CacheService cache, QueryLogRepository queryLog,
                                 QueryReadiness readiness, ExecutorService virtualExecutor,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${wisteria.gate.furniture-enabled:true}") boolean gateEnabled,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${wisteria.gate.furniture-threshold:0.5}") double furnitureThreshold) {
        this.normalizer = normalizer;
        this.clip = clip;
        this.extractor = extractor;
        this.repository = repository;
        this.reRanker = reRanker;
        this.explainer = explainer;
        this.cache = cache;
        this.queryLog = queryLog;
        this.readiness = readiness;
        this.executor = virtualExecutor;
        this.gateEnabled = gateEnabled;
        this.furnitureThreshold = furnitureThreshold;
    }

    @Override
    public StyleQueryResponse query(MultipartFile image, int topK,
                                    String filterEra, String filterMaterial) {
        long start = System.currentTimeMillis();

        if (readiness.isBlocked()) {
            throw new ModelVersionMismatchException(readiness.reason());
        }

        // User uploads accept any decodable image type (no JPEG/PNG gate).
        NormalizedImage norm = normalizer.normalizeAnyType(readBytes(image));
        long phash = cache.perceptualHash(norm.image());

        var cached = cache.get(phash);
        if (cached.isPresent()) {
            return cacheHit(cached.get(), phash, start);
        }

        // Parallel fan-out: CLIP embed (required) + furniture gate + Claude (degradable).
        CompletableFuture<ClipClient.EmbedResult> embedF =
                CompletableFuture.supplyAsync(() -> clip.embed(norm.base64()), executor);
        CompletableFuture<ClipClient.FurnitureVerdict> gateF =
                CompletableFuture.supplyAsync(() -> clip.classify(norm.base64()), executor);
        CompletableFuture<StyleAttributes> attrF =
                CompletableFuture.supplyAsync(() -> extractQuietly(norm.base64()), executor);

        ClipClient.EmbedResult emb = awaitEmbedding(embedF, start);

        // Open-set rejection: if the image isn't furniture, short-circuit BEFORE the
        // VLM/DB/re-rank work. No closest-matches, no inflated score card — just a
        // distinct out-of-domain response. Not cached (a transient gate blip
        // shouldn't pin a no-results answer for the whole TTL).
        ClipClient.FurnitureVerdict gate = awaitGate(gateF);
        if (gateEnabled && gate.furnitureProb() < furnitureThreshold) {
            attrF.cancel(true);
            log.info("Out-of-domain query rejected by furniture gate: prob={} top='{}'",
                    String.format("%.3f", gate.furnitureProb()), gate.topLabel());
            StyleQueryResponse ood = new StyleQueryResponse(
                    UUID.randomUUID(), false, elapsed(start), true, true,
                    false,   // vlm_degraded: VLM was intentionally skipped, not degraded
                    toQueryAttributes(null), List.of());
            queryLog.log(ood.queryId(), phash, (int) ood.latencyMs(), false, 0);
            return ood;
        }

        StyleAttributes queryAttrs = awaitAttributes(attrF, start);

        List<CandidateRow> candidates =
                repository.findTopCandidates(emb.embedding(), filterEra, filterMaterial, CANDIDATE_POOL);
        RankingOutcome outcome = reRanker.rank(queryAttrs, candidates, topK);

        Map<UUID, String> explanations = (queryAttrs != null && !outcome.ranked().isEmpty())
                ? explainer.explain(queryAttrs, outcome.ranked(), outcome.lowConfidence())
                : Map.of();

        List<ResultItem> results = outcome.ranked().stream()
                .map(sc -> toResultItem(sc, explanations, outcome.lowConfidence()))
                .toList();

        long latency = elapsed(start);
        StyleQueryResponse response = new StyleQueryResponse(
                UUID.randomUUID(), false, latency, outcome.lowConfidence(), false,
                queryAttrs == null,   // vlm_degraded: VLM unavailable → embedding-only fallback
                toQueryAttributes(queryAttrs), results);

        // Only cache full-fidelity responses. A vision-degraded run (VLM down →
        // queryAttrs == null) is CLIP-only with empty attributes; caching it
        // would pin those attribute-less results under the image's pHash for the
        // whole TTL and keep serving them even after the VLM recovers (same image
        // → same pHash → cache hit). Skipping the write lets the next query
        // recompute the full result once vision is back.
        if (queryAttrs != null) {
            cache.put(phash, response);
        }
        queryLog.log(response.queryId(), phash, (int) latency, false, outcome.topScore());
        return response;
    }

    /* ── pipeline helpers ─────────────────────────────────── */

    private byte[] readBytes(MultipartFile image) {
        try {
            return image.getBytes();
        } catch (Exception e) {
            throw new UnsupportedImageException("could not read uploaded image: " + e.getMessage());
        }
    }

    private ClipClient.EmbedResult awaitEmbedding(CompletableFuture<ClipClient.EmbedResult> f, long start) {
        try {
            return f.get(remaining(start), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new EmbeddingUnavailableException("embedding_timeout: " + cause.getMessage(), cause);
        }
    }

    /** Gate verdict — degrades open (allow the query) on any failure. */
    private ClipClient.FurnitureVerdict awaitGate(CompletableFuture<ClipClient.FurnitureVerdict> f) {
        try {
            return f.join();
        } catch (Exception e) {
            log.warn("Furniture gate degraded open: {}", e.getMessage());
            return ClipClient.FurnitureVerdict.OPEN;
        }
    }

    private StyleAttributes awaitAttributes(CompletableFuture<StyleAttributes> f, long start) {
        try {
            return f.get(remaining(start), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Vision extraction degraded (embedding-only search): {}", e.getMessage());
            f.cancel(true);
            return null;
        }
    }

    /** Vision call that never throws — returns null so the caller degrades. */
    private StyleAttributes extractQuietly(String base64) {
        try {
            return extractor.extract(base64);
        } catch (Exception e) {
            log.warn("Vision extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private ResultItem toResultItem(ScoredCandidate sc, Map<UUID, String> explanations, boolean lowConfidence) {
        String why = explanations.get(sc.row().productId());
        if (!passesQualityGate(why, sc)) {
            why = templateExplanation(sc, lowConfidence);
        }
        return new ResultItem(
                sc.row().productId(),
                sc.row().name(),
                imageUrl(sc.row().imagePath()),
                sc.apiScore(),
                sc.matchedAttributes(),
                why);
    }

    /** Reject too-short explanations or ones that cite no concrete attribute. */
    private static boolean passesQualityGate(String why, ScoredCandidate sc) {
        if (why == null || why.isBlank()) {
            return false;
        }
        if (why.trim().split("\\s+").length < MIN_EXPLANATION_WORDS) {
            return false;
        }
        String lower = why.toLowerCase();
        return sc.row().attributes().asMap().values().stream()
                .filter(v -> v != null && !v.isBlank())
                .anyMatch(v -> lower.contains(v.toLowerCase()));
    }

    // Human-readable noun for each closed-vocab category value.
    private static final Map<String, String> CATEGORY_NOUNS = Map.of(
            "seating", "seating piece",
            "table", "table",
            "storage", "storage piece",
            "bed", "bed",
            "lighting", "lighting piece",
            "rug", "rug",
            "decor", "décor piece");

    /**
     * Fallback explanation built from the PRODUCT's own indexed attributes —
     * always available, even when query-side vision degrades (e.g. VLM down),
     * so each result reads distinctly instead of one generic line. CLIP visual
     * similarity is the real reason for the match, so we frame it that way.
     *
     * e.g. "A contemporary wood seating piece with a clean-line silhouette and
     *       warm-neutral palette — a close visual match to your inspiration image."
     */
    private static String templateExplanation(ScoredCandidate sc, boolean lowConfidence) {
        CandidateRow r = sc.row();
        // Honest tail when the re-ranker isn't confident (e.g. out-of-domain query).
        String closer = lowConfidence
                ? " — one of the closest in style we could find, though it may not be a strong match."
                : " — a close visual match to your inspiration image.";

        List<String> lead = new java.util.ArrayList<>();   // adjectives before the noun
        addIfPresent(lead, r.era());
        addIfPresent(lead, r.material());

        String noun = categoryNoun(r.furnitureCategory());

        List<String> traits = new java.util.ArrayList<>();  // trailing descriptors
        if (isPresent(r.silhouette())) traits.add(r.silhouette().toLowerCase() + " silhouette");
        if (isPresent(r.palette()))    traits.add(r.palette().toLowerCase() + " palette");
        else if (isPresent(r.finish())) traits.add(r.finish().toLowerCase() + " finish");

        if (lead.isEmpty() && noun == null && traits.isEmpty()) {
            return lowConfidence
                    ? "One of the closest in style we could find, though it may not be a strong match."
                    : "A close visual match to the overall look of your inspiration image.";
        }

        String head = !lead.isEmpty() ? lead.get(0) : (noun != null ? noun : "piece");
        StringBuilder s = new StringBuilder(capitalize(article(head))).append(' ');
        if (!lead.isEmpty()) s.append(String.join(" ", lead)).append(' ');
        s.append(noun != null ? noun : "piece");
        if (!traits.isEmpty()) {
            s.append(" with ").append(article(traits.get(0))).append(' ').append(joinAnd(traits));
        }
        s.append(closer);
        return s.toString();
    }

    /** "a"/"an" by the first sound of the word (vowel-letter heuristic). */
    private static String article(String word) {
        if (word == null || word.isEmpty()) return "a";
        return "aeiou".indexOf(Character.toLowerCase(word.charAt(0))) >= 0 ? "an" : "a";
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String categoryNoun(String category) {
        if (!isPresent(category)) return null;
        return CATEGORY_NOUNS.getOrDefault(category.toLowerCase(), category.toLowerCase());
    }

    private static void addIfPresent(List<String> list, String v) {
        if (isPresent(v)) list.add(v.toLowerCase());
    }

    private static boolean isPresent(String v) {
        return v != null && !v.isBlank();
    }

    private static String joinAnd(List<String> parts) {
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }

    private StyleQueryResponse cacheHit(StyleQueryResponse cached, long phash, long start) {
        UUID queryId = UUID.randomUUID();   // each request is a distinct logged event
        long latency = elapsed(start);
        int topScore = cached.results().isEmpty() ? 0 : cached.results().get(0).similarityScore();
        queryLog.log(queryId, phash, (int) latency, true, topScore);
        return new StyleQueryResponse(
                queryId, true, latency, cached.lowConfidence(), cached.outOfDomain(),
                cached.vlmDegraded(),   // always false in practice — degraded runs are never cached
                cached.queryAttributes(), cached.results());
    }

    private static QueryAttributes toQueryAttributes(StyleAttributes a) {
        if (a == null) {
            return new QueryAttributes(null, null, null, null, null, null, null);
        }
        return new QueryAttributes(a.furnitureCategory(), a.finish(), a.material(),
                a.silhouette(), a.era(), a.palette(), a.mood());
    }

    private static String imageUrl(String imagePath) {
        String rel = imagePath != null && imagePath.startsWith("/") ? imagePath.substring(1) : imagePath;
        return "/catalog/" + rel;
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private static long remaining(long start) {
        return Math.max(1, DEADLINE_MS - elapsed(start));
    }
}
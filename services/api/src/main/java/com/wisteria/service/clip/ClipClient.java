package com.wisteria.service.clip;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wisteria.exception.WisteriaExceptions.EmbeddingUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Thin HTTP client for the Python CLIP server (LLD v2.1 Day 1).
 *   POST /embed  {image_b64} → {embedding[768], model_ver}
 *   GET  /health             → {status, device, model_ver}
 *
 * Embeddings are the backbone of the pipeline, so any failure surfaces as
 * {@link EmbeddingUnavailableException} → 503 + retry_after.
 */
@Slf4j
@Component
public class ClipClient {

    private final RestClient clip;

    public ClipClient(RestClient clipRestClient) {
        this.clip = clipRestClient;
    }

    public EmbedResult embed(String imageB64) {
        try {
            EmbedResponse r = clip.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("image_b64", imageB64))
                    .retrieve()
                    .body(EmbedResponse.class);
            if (r == null || r.embedding() == null || r.embedding().length == 0) {
                throw new IllegalStateException("CLIP returned an empty embedding");
            }
            return new EmbedResult(r.embedding(), r.modelVer());
        } catch (Exception e) {
            throw new EmbeddingUnavailableException("CLIP /embed failed: " + e.getMessage(), e);
        }
    }

    /**
     * Zero-shot domain gate: how strongly the image reads as furniture (image→text
     * CLIP). Degrades <em>open</em> — if the gate is unreachable or errors, we return
     * {@code furniture_prob = 1.0} so a gate outage never blocks legitimate queries.
     */
    public FurnitureVerdict classify(String imageB64) {
        try {
            ClassifyResponse r = clip.post()
                    .uri("/classify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("image_b64", imageB64))
                    .retrieve()
                    .body(ClassifyResponse.class);
            if (r == null) {
                return FurnitureVerdict.OPEN;
            }
            return new FurnitureVerdict(r.furnitureProb(), r.topLabel(), r.topIsFurniture());
        } catch (Exception e) {
            log.warn("CLIP /classify failed, allowing query (gate degraded open): {}", e.getMessage());
            return FurnitureVerdict.OPEN;
        }
    }

    /** Live model version from the CLIP server, or throws if unreachable. */
    public String modelVersion() {
        try {
            HealthResponse r = clip.get().uri("/health").retrieve().body(HealthResponse.class);
            return r == null ? null : r.modelVer();
        } catch (Exception e) {
            throw new EmbeddingUnavailableException("CLIP /health failed: " + e.getMessage(), e);
        }
    }

    public boolean isHealthy() {
        try {
            return modelVersion() != null;
        } catch (Exception e) {
            log.debug("CLIP health check failed: {}", e.getMessage());
            return false;
        }
    }

    /** Result of an embed call: the 768-dim vector + the model that produced it. */
    public record EmbedResult(float[] embedding, String modelVer) {}

    /**
     * Furniture-gate verdict. {@code furnitureProb} is the softmax mass on the
     * furniture prompt group; {@code topLabel} is the single best-matching prompt.
     */
    public record FurnitureVerdict(double furnitureProb, String topLabel, boolean topIsFurniture) {
        /** Permissive default used when the gate is unavailable (degrade open). */
        public static final FurnitureVerdict OPEN = new FurnitureVerdict(1.0, "unknown", true);
    }

    record EmbedResponse(float[] embedding, @JsonProperty("model_ver") String modelVer) {}

    record ClassifyResponse(
            @JsonProperty("furniture_prob") double furnitureProb,
            @JsonProperty("top_label") String topLabel,
            @JsonProperty("top_is_furniture") boolean topIsFurniture) {}

    record HealthResponse(String status, String device, @JsonProperty("model_ver") String modelVer) {}
}
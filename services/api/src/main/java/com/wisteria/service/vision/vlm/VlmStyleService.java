package com.wisteria.service.vision.vlm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisteria.model.StyleAttributes;
import com.wisteria.service.vision.StyleExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Self-hosted Qwen2.5-VL style extractor (LLD v3.0). HTTP client for the Python
 * VLM server (:5002), mirroring {@link com.wisteria.service.clip.ClipClient}.
 *   POST /extract {image_b64} → {category, finish, material, silhouette, era, palette, mood, model_ver}
 *   GET  /health             → {status, device, model_ver}
 *
 * Active when {@code wisteria.vision.provider=vlm}. Throws on failure; the
 * caller degrades to embedding-only search (same contract as the Claude path).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "wisteria.vision.provider", havingValue = "vlm", matchIfMissing = true)
public class VlmStyleService implements StyleExtractor {

    private final RestClient vlm;
    private final ObjectMapper mapper = new ObjectMapper();

    public VlmStyleService(RestClient vlmRestClient) {
        this.vlm = vlmRestClient;
    }

    @Override
    public StyleAttributes extract(String jpegBase64) {
        ExtractResponse r = vlm.post()
                .uri("/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("image_b64", jpegBase64))
                .retrieve()
                .body(ExtractResponse.class);
        if (r == null || r.category() == null) {
            throw new IllegalStateException("VLM /extract returned no attributes");
        }
        return new StyleAttributes(
                r.category(), r.finish(), r.material(), r.silhouette(),
                r.era(), r.palette(), r.mood(), rawJson(r));
    }

    @Override
    public String modelVersion() {
        HealthResponse r = vlm.get().uri("/health").retrieve().body(HealthResponse.class);
        return r == null ? null : r.modelVer();
    }

    private String rawJson(ExtractResponse r) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("category", r.category());
        m.put("finish", r.finish());
        m.put("material", r.material());
        m.put("silhouette", r.silhouette());
        m.put("era", r.era());
        m.put("palette", r.palette());
        m.put("mood", r.mood());
        try {
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    record ExtractResponse(String category, String finish, String material, String silhouette,
                           String era, String palette, String mood,
                           @JsonProperty("model_ver") String modelVer) {}

    record HealthResponse(String status, String device, @JsonProperty("model_ver") String modelVer) {}
}

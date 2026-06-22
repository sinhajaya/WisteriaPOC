package com.wisteria.controller;

import com.wisteria.model.StyleQueryResponse;
import com.wisteria.service.HealthService;
import com.wisteria.service.StyleQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Core customer-facing API.
 *
 * POST /api/v1/style/query  — inspiration image in, Top-K matched products out.
 * GET  /api/v1/style/health — dependency health (CLIP, pgvector, Redis, Claude).
 *
 * Contract is production-ready: shape does not change when the POC
 * moves to SageMaker / RDS / ElastiCache (LLD v2.1).
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/style")
@RequiredArgsConstructor
@CrossOrigin(origins = "${wisteria.cors.allowed-origin:http://localhost:3000}")
public class StyleQueryController {

    private final StyleQueryService styleQueryService;
    private final HealthService healthService;

    /**
     * Submit an inspiration image. Returns Top-K matched products with
     * 0–100 similarity scores and "why this matches" explanations.
     *
     * @param image          JPEG / PNG, max 10 MB (validated by magic bytes downstream)
     * @param topK           number of results (default 8, max 20)
     * @param filterEra      optional pre-filter, e.g. "mid-century"
     * @param filterMaterial optional pre-filter, e.g. "rattan"
     */
    @PostMapping(value = "/query", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StyleQueryResponse> query(
            @RequestParam("image") MultipartFile image,
            @RequestParam(name = "top_k", defaultValue = "8") @Min(1) @Max(20) int topK,
            @RequestParam(name = "filter_era", required = false) String filterEra,
            @RequestParam(name = "filter_material", required = false) String filterMaterial) {

        log.info("Style query received: file={} size={}B topK={} filterEra={} filterMaterial={}",
                image.getOriginalFilename(), image.getSize(), topK, filterEra, filterMaterial);

        StyleQueryResponse response =
                styleQueryService.query(image, topK, filterEra, filterMaterial);

        return ResponseEntity.ok(response);
    }

    /**
     * Aggregated dependency health. Used by the React UI at startup to
     * show a "not ready" state while the CLIP model is still loading.
     *
     * 200 → {"clip":"ok","postgres":"ok","redis":"ok","claude":"ok"}
     * 503 → same body, at least one dependency "down"
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> status = healthService.checkAll();
        boolean allOk = status.values().stream().allMatch("ok"::equals);
        return allOk
                ? ResponseEntity.ok(status)
                : ResponseEntity.status(503).body(status);
    }
}

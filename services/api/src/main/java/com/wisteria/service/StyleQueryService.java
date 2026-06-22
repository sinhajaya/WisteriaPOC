package com.wisteria.service;

import com.wisteria.model.StyleQueryResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * Orchestrates the full online pipeline (LLD v2.1 Day 3–4):
 * validate → pHash cache probe → parallel CLIP + Claude extraction
 * → ANN search (ef_search=50, Top 50) → weighted re-rank (0.65/0.35)
 * → batched explanation → cache write → response.
 *
 * Implementation lands in Day 3; controller layer is mock-testable now.
 */
public interface StyleQueryService {

    StyleQueryResponse query(MultipartFile image, int topK,
                             String filterEra, String filterMaterial);
}

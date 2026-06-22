package com.wisteria.service.vision;

import com.wisteria.model.StyleAttributes;

/**
 * Vision seam: extracts style attributes from a normalised JPEG. Selected by
 * {@code wisteria.vision.provider} — {@code claude} (LLD v2.1) or {@code vlm}
 * (self-hosted Qwen2.5-VL, LLD v3.0). The rest of the pipeline depends only on
 * this interface.
 */
public interface StyleExtractor {

    /** Extract style attributes from a Base64 JPEG. Throws on failure. */
    StyleAttributes extract(String jpegBase64);

    /** Live model version for the version guard, or {@code null} if not exposed. */
    default String modelVersion() {
        return null;
    }
}
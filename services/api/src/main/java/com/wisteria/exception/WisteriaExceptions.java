package com.wisteria.exception;

/**
 * Domain exceptions thrown by the service layer, translated to
 * machine-readable HTTP errors by {@link GlobalExceptionHandler}.
 *
 * Error-code strings are part of the API contract — the React UI
 * switches on them (retry button, degraded banners, etc.).
 */
public final class WisteriaExceptions {

    private WisteriaExceptions() {}

    /** 415 — magic-byte validation failed (not JPEG/PNG). */
    public static class UnsupportedImageException extends RuntimeException {
        public UnsupportedImageException(String msg) { super(msg); }
    }

    /** 413 — image exceeds 10 MB cap. */
    public static class ImageTooLargeException extends RuntimeException {
        public ImageTooLargeException(String msg) { super(msg); }
    }

    /** 503 — CLIP server unreachable or timed out. Embeddings are the backbone. */
    public static class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String msg, Throwable cause) { super(msg, cause); }
    }

    /** 503 — indexed model_ver differs from CLIP server's; re-index required. */
    public static class ModelVersionMismatchException extends RuntimeException {
        public ModelVersionMismatchException(String msg) { super(msg); }
    }
}

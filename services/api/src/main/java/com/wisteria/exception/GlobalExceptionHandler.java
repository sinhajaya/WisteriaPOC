package com.wisteria.exception;

import com.wisteria.exception.WisteriaExceptions.*;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Maps domain exceptions to the machine-readable error contract
 * defined in the LLD v2.1 failure-mode table.
 *
 * Design rule: only the CLIP/embedding path may fail a request.
 * Claude extraction, explanations, and Redis all degrade inside the
 * service layer and never surface here.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 415 — wrong format (magic bytes), per LLD v2.1 input validation. */
    @ExceptionHandler(UnsupportedImageException.class)
    public ResponseEntity<Map<String, Object>> unsupportedImage(UnsupportedImageException ex) {
        return ResponseEntity.status(415).body(Map.of(
                "error", "unsupported_image_format",
                "detail", "Only JPEG and PNG are accepted",
                "message", ex.getMessage()));
    }

    /** 413 — over the 10 MB cap (domain check or Spring's multipart limit). */
    @ExceptionHandler({ImageTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<Map<String, Object>> tooLarge(Exception ex) {
        return ResponseEntity.status(413).body(Map.of(
                "error", "image_too_large",
                "detail", "Maximum upload size is 10 MB"));
    }

    /** 503 + retry_after — CLIP server down/slow. React shows a retry button. */
    @ExceptionHandler(EmbeddingUnavailableException.class)
    public ResponseEntity<Map<String, Object>> embeddingDown(EmbeddingUnavailableException ex) {
        log.error("Embedding service unavailable", ex);
        return ResponseEntity.status(503).body(Map.of(
                "error", "embedding_timeout",
                "retry_after", 5));
    }

    /** 503 — CLIP model version drift; queries blocked until re-index. */
    @ExceptionHandler(ModelVersionMismatchException.class)
    public ResponseEntity<Map<String, Object>> modelMismatch(ModelVersionMismatchException ex) {
        log.error("Model version mismatch: {}", ex.getMessage());
        return ResponseEntity.status(503).body(Map.of(
                "error", "reindex_required",
                "detail", ex.getMessage()));
    }

    /** 400 — missing "image" multipart part. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> missingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "missing_image",
                "detail", "Multipart field 'image' is required"));
    }

    /** 400 — top_k out of range etc. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> badParams(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_parameter",
                "detail", ex.getMessage()));
    }

    /** 404 — unmapped path or missing static resource (e.g. /favicon.ico). Not an error; no stack trace. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex) {
        return ResponseEntity.status(404).body(Map.of(
                "error", "not_found",
                "detail", "No handler for " + ex.getResourcePath()));
    }

    /** 500 — last resort. Never leak stack traces to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        log.error("Unhandled exception in API layer", ex);
        return ResponseEntity.internalServerError().body(Map.of(
                "error", "internal_error"));
    }
}

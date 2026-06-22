package com.wisteria.model;

/**
 * Response of GET /api/v1/admin/index-status.
 */
public record IndexStatusResponse(
        Status status,
        int total,
        int indexed,
        int skipped,
        long elapsedMs,
        String error          // populated only when status == FAILED
) {
    public enum Status { IDLE, RUNNING, DONE, FAILED }
}

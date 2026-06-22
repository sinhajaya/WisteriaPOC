package com.wisteria.controller;

import com.wisteria.model.IndexStatusResponse;
import com.wisteria.service.CatalogIndexService;
import com.wisteria.service.IndexJobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin API — catalog indexing lifecycle.
 *
 * POST /api/v1/admin/index        — trigger indexing (202, or 409 if already running)
 * GET  /api/v1/admin/index-status — poll job progress
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final CatalogIndexService catalogIndexService;
    private final IndexJobStatus indexJobStatus;

    /**
     * Triggers a full catalog indexing run (CLIP embeddings + Claude style
     * attributes for every product). Asynchronous: returns immediately.
     *
     * 202 Accepted  — run started, poll /index-status
     * 409 Conflict  — a run is already in progress (AtomicBoolean guard,
     *                 LLD v2.1 Day-2 fix: prevents double-trigger corruption)
     */
    @PostMapping("/index")
    public ResponseEntity<Map<String, String>> triggerIndexing() {
        boolean started = catalogIndexService.tryStartIndexing();
        if (!started) {
            log.warn("Indexing trigger rejected: a run is already active");
            return ResponseEntity.status(409).body(Map.of(
                    "error", "indexing_already_running",
                    "hint", "Poll GET /api/v1/admin/index-status"));
        }
        log.info("Catalog indexing run started");
        return ResponseEntity.accepted().body(Map.of(
                "status", "STARTED",
                "statusUrl", "/api/v1/admin/index-status"));
    }

    /**
     * Current indexing job state.
     * status: IDLE | RUNNING | DONE | FAILED
     */
    @GetMapping("/index-status")
    public IndexStatusResponse indexStatus() {
        return indexJobStatus.snapshot();
    }
}

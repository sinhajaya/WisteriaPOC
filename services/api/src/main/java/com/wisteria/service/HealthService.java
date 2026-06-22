package com.wisteria.service;

import java.util.Map;

/**
 * Aggregated dependency health for GET /api/v1/style/health.
 * Each key maps to "ok" or "down":
 *   clip     — GET :5001/health reachable
 *   postgres — SELECT 1 succeeds
 *   redis    — PING succeeds (degraded-only dependency)
 *   claude   — API key present / last call status
 */
public interface HealthService {

    Map<String, String> checkAll();
}

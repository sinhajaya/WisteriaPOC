package com.wisteria.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Append-only analytics for each served query (LLD v2.1 {@code style_query_log}):
 * latency, cache effectiveness, and top match score for precision tracking.
 */
@Repository
public class QueryLogRepository {

    private static final String INSERT = """
            INSERT INTO style_query_log (query_id, phash, latency_ms, cache_hit, top_score)
            VALUES (:qid, :phash, :latency, :cacheHit, :topScore)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public QueryLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(UUID queryId, long phash, int latencyMs, boolean cacheHit, int topScore) {
        jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("qid", queryId)
                .addValue("phash", phash)
                .addValue("latency", latencyMs)
                .addValue("cacheHit", cacheHit)
                .addValue("topScore", topScore));
    }
}
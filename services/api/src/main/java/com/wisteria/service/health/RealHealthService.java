package com.wisteria.service.health;

import com.wisteria.service.HealthService;
import com.wisteria.service.clip.ClipClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregated dependency health (LLD v2.1). Each probe is isolated so one
 * dependency being down never throws — it just reports {@code "down"}.
 */
@Slf4j
@Service
public class RealHealthService implements HealthService {

    private final ClipClient clip;
    private final NamedParameterJdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final boolean claudeKeyPresent;

    public RealHealthService(ClipClient clip, NamedParameterJdbcTemplate jdbc,
                             StringRedisTemplate redis,
                             @Value("${anthropic.api-key:}") String apiKey) {
        this.clip = clip;
        this.jdbc = jdbc;
        this.redis = redis;
        this.claudeKeyPresent = apiKey != null && !apiKey.isBlank();
    }

    @Override
    public Map<String, String> checkAll() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("clip", clip.isHealthy() ? "ok" : "down");
        status.put("postgres", postgresOk() ? "ok" : "down");
        status.put("redis", redisOk() ? "ok" : "down");
        status.put("claude", claudeKeyPresent ? "ok" : "down");
        return status;
    }

    private boolean postgresOk() {
        try {
            jdbc.getJdbcTemplate().queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.debug("Postgres health probe failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean redisOk() {
        try {
            return "PONG".equalsIgnoreCase(redis.getConnectionFactory().getConnection().ping());
        } catch (Exception e) {
            log.debug("Redis health probe failed: {}", e.getMessage());
            return false;
        }
    }
}

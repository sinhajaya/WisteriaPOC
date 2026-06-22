package com.wisteria.service.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisteria.model.StyleQueryResponse;
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Optional;

/**
 * Redis perceptual-hash response cache (LLD v2.1). The cache key is a 64-bit
 * DCT pHash of the NORMALISED image, so re-uploads at different JPEG quality /
 * size still hit. Every Redis call is wrapped — Redis being down degrades to a
 * full pipeline run, never a failed request.
 */
@Slf4j
@Service
public class CacheService {

    private static final String PREFIX = "style:query:";
    private static final Duration TTL = Duration.ofSeconds(3600);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PerceptiveHash hasher = new PerceptiveHash(64);

    public CacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 64-bit DCT perceptual hash of the normalised image. */
    public long perceptualHash(BufferedImage image) {
        return hasher.hash(image).getHashValue().longValue();
    }

    public Optional<StyleQueryResponse> get(long phash) {
        try {
            String json = redis.opsForValue().get(key(phash));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(json, StyleQueryResponse.class));
        } catch (Exception e) {
            log.warn("Cache read skipped (degrading to full pipeline): {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(long phash, StyleQueryResponse response) {
        try {
            redis.opsForValue().set(key(phash), mapper.writeValueAsString(response), TTL);
        } catch (Exception e) {
            log.warn("Cache write skipped: {}", e.getMessage());
        }
    }

    private static String key(long phash) {
        return PREFIX + Long.toHexString(phash);
    }
}
package com.wisteria.config;

import com.wisteria.repository.ProductRepository;
import com.wisteria.service.QueryReadiness;
import com.wisteria.service.clip.ClipClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Startup guard (LLD v2.1): a CLIP version mismatch between indexing and query
 * corrupts the embedding space. On boot, if the catalog is non-empty, compare
 * the indexed {@code model_ver}(s) against the live CLIP server; on mismatch,
 * block queries ({@code reindex_required}) until the catalog is re-indexed.
 */
@Slf4j
@Component
public class ModelVersionGuard {

    private final ProductRepository repository;
    private final ClipClient clip;
    private final QueryReadiness readiness;

    public ModelVersionGuard(ProductRepository repository, ClipClient clip, QueryReadiness readiness) {
        this.repository = repository;
        this.clip = clip;
        this.readiness = readiness;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        try {
            if (repository.countEmbeddings() == 0) {
                log.info("Model-version guard: catalog empty, nothing to verify");
                return;
            }
            List<String> indexed = repository.distinctModelVersions();
            String live = clip.modelVersion();
            boolean mismatch = indexed.stream().anyMatch(v -> v != null && !v.equals(live));
            if (mismatch) {
                String reason = "reindex_required: indexed model_ver " + indexed
                        + " != CLIP server " + live;
                readiness.block(reason);
                log.error("Model-version guard BLOCKING queries — {}", reason);
            } else {
                readiness.clear();
                log.info("Model-version guard OK: indexed {} matches CLIP {}", indexed, live);
            }
        } catch (Exception e) {
            // CLIP unreachable at boot is a health concern, not a reason to block.
            log.warn("Model-version guard skipped (could not verify): {}", e.getMessage());
        }
    }
}
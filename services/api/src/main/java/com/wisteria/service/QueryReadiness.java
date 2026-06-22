package com.wisteria.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared gate consulted by the online query path. Set by the startup
 * model-version guard (LLD v2.1): if the indexed CLIP {@code model_ver}
 * differs from the live CLIP server's, queries are blocked with
 * {@code reindex_required} until the catalog is re-indexed.
 */
@Component
public class QueryReadiness {

    private final AtomicBoolean blocked = new AtomicBoolean(false);
    private final AtomicReference<String> reason = new AtomicReference<>();

    public void block(String why) {
        reason.set(why);
        blocked.set(true);
    }

    public void clear() {
        reason.set(null);
        blocked.set(false);
    }

    public boolean isBlocked() {
        return blocked.get();
    }

    public String reason() {
        return reason.get();
    }
}
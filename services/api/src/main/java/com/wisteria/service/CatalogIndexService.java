package com.wisteria.service;

/**
 * Offline catalog indexing (LLD v2.1 Day 2): products.json →
 * parallel CLIP embedding + Claude attribute extraction → pgvector upsert.
 */
public interface CatalogIndexService {

    /**
     * Atomically attempts to start an indexing run.
     *
     * Implementation contract: an {@code AtomicBoolean.compareAndSet(false, true)}
     * guard — returns {@code false} without side effects if a run is already
     * active. The controller maps {@code false} to 409 Conflict.
     *
     * The run itself executes asynchronously (virtual thread), publishing
     * progress to {@link IndexJobStatus}.
     */
    boolean tryStartIndexing();
}

package com.wisteria.service;

import com.wisteria.model.IndexStatusResponse;
import com.wisteria.model.IndexStatusResponse.Status;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe, in-memory state of the (single) indexing job.
 * Written by CatalogIndexService on a background virtual thread,
 * read by AdminController on request threads — hence atomics.
 *
 * POC-scoped on purpose: state is lost on restart, which is fine
 * because the indexed data itself lives in PostgreSQL.
 */
@Component
public class IndexJobStatus {

    private final AtomicReference<Status> status = new AtomicReference<>(Status.IDLE);
    private final AtomicInteger total   = new AtomicInteger();
    private final AtomicInteger indexed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicLong startedAt  = new AtomicLong();
    private final AtomicReference<String> error = new AtomicReference<>();

    /* ── written by the indexing job ───────────────────────── */

    public void markRunning(int totalProducts) {
        total.set(totalProducts);
        indexed.set(0);
        skipped.set(0);
        error.set(null);
        startedAt.set(System.currentTimeMillis());
        status.set(Status.RUNNING);
    }

    public void incrementIndexed() { indexed.incrementAndGet(); }

    public void incrementSkipped() { skipped.incrementAndGet(); }

    public void markDone() { status.set(Status.DONE); }

    public void markFailed(String reason) {
        error.set(reason);
        status.set(Status.FAILED);
    }

    /* ── read by AdminController ───────────────────────────── */

    public IndexStatusResponse snapshot() {
        long elapsed = startedAt.get() == 0
                ? 0
                : System.currentTimeMillis() - startedAt.get();
        return new IndexStatusResponse(
                status.get(),
                total.get(),
                indexed.get(),
                skipped.get(),
                elapsed,
                error.get());
    }
}

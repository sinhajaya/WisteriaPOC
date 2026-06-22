package com.wisteria.service.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisteria.model.ProductEntry;
import com.wisteria.model.StyleAttributes;
import com.wisteria.service.CatalogIndexService;
import com.wisteria.service.ImageNormalizer;
import com.wisteria.service.IndexJobStatus;
import com.wisteria.service.NormalizedImage;
import com.wisteria.service.clip.ClipClient;
import com.wisteria.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real offline catalog indexer (LLD v2.1 Day 2).
 *
 * products.json → per product: read image → normalize → parallel{CLIP embed,
 * style attributes} on virtual threads (bounded by orTimeout(30s)) → upsert
 * embedding + 6 style attributes in one transaction.
 *
 * Style attributes come from a pluggable {@link CatalogAttributeSource}
 * ({@code wisteria.catalog.attribute-source}): the live extractor (default) or
 * the Claude teacher labels. This is independent of the QUERY path, which keeps
 * calling the VLM directly.
 *
 * Hardening per v2.1: AtomicBoolean guard (409 on double-trigger); the source
 * skips-and-counts a single bad product (malformed output / missing label) so
 * one product never aborts the whole batch.
 */
@Slf4j
@Service
public class RealCatalogIndexService implements CatalogIndexService {

    private static final long PER_PRODUCT_TIMEOUT_SEC = 30;

    private final ImageNormalizer normalizer;
    private final ClipClient clip;
    private final CatalogAttributeSource attributeSource;
    private final ProductRepository repository;
    private final IndexJobStatus status;
    private final ExecutorService virtualExecutor;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String productsFile;
    private final String imageDir;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RealCatalogIndexService(ImageNormalizer normalizer, ClipClient clip,
                                   CatalogAttributeSource attributeSource, ProductRepository repository,
                                   IndexJobStatus status, ExecutorService virtualExecutor,
                                   ResourceLoader resourceLoader,
                                   @Value("${wisteria.catalog.products-file}") String productsFile,
                                   @Value("${wisteria.catalog.image-dir}") String imageDir) {
        this.normalizer = normalizer;
        this.clip = clip;
        this.attributeSource = attributeSource;
        this.repository = repository;
        this.status = status;
        this.virtualExecutor = virtualExecutor;
        this.resourceLoader = resourceLoader;
        this.productsFile = productsFile;
        this.imageDir = imageDir;
    }

    @Override
    public boolean tryStartIndexing() {
        if (!running.compareAndSet(false, true)) {
            return false;                       // → controller returns 409
        }
        Thread.startVirtualThread(this::run);
        return true;                            // → controller returns 202
    }

    private void run() {
        try {
            List<ProductEntry> products = loadCatalog();
            status.markRunning(products.size());
            log.info("Indexing run started: {} products", products.size());

            for (ProductEntry entry : products) {
                try {
                    indexOne(entry);
                    status.incrementIndexed();
                } catch (Exception e) {
                    log.warn("Skipping product '{}' ({}): {}",
                            entry.name(), entry.imagePath(), e.getMessage());
                    status.incrementSkipped();
                }
            }
            status.markDone();
            log.info("Indexing run complete");
        } catch (Exception fatal) {
            log.error("Indexing run failed", fatal);
            status.markFailed(fatal.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void indexOne(ProductEntry entry) {
        UUID productId = entry.idOrDerived();
        byte[] imageBytes = readImage(entry.imagePath());
        NormalizedImage img = normalizer.normalize(imageBytes);

        CompletableFuture<ClipClient.EmbedResult> embedF =
                CompletableFuture.supplyAsync(() -> clip.embed(img.base64()), virtualExecutor);
        CompletableFuture<StyleAttributes> attrF =
                CompletableFuture.supplyAsync(() -> attributeSource.attributesFor(entry, img.base64()), virtualExecutor);

        CompletableFuture.allOf(embedF, attrF)
                .orTimeout(PER_PRODUCT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .join();

        ClipClient.EmbedResult emb = embedF.join();
        StyleAttributes attrs = attrF.join();
        repository.upsertProduct(entry, productId, emb.embedding(), emb.modelVer(), attrs);
    }

    private List<ProductEntry> loadCatalog() throws Exception {
        Resource resource = resourceLoader.getResource(productsFile);
        if (!resource.exists()) {
            throw new IllegalStateException("catalog manifest not found: " + productsFile);
        }
        try (var in = resource.getInputStream()) {
            return mapper.readValue(in, new TypeReference<List<ProductEntry>>() {});
        }
    }

    private byte[] readImage(String imagePath) {
        String base = imageDir.endsWith("/") ? imageDir : imageDir + "/";
        String rel = imagePath.startsWith("/") ? imagePath.substring(1) : imagePath;
        Resource resource = resourceLoader.getResource(base + rel);
        if (!resource.exists()) {
            throw new IllegalStateException("image not found: " + base + rel);
        }
        try (var in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("could not read image " + rel + ": " + e.getMessage(), e);
        }
    }
}
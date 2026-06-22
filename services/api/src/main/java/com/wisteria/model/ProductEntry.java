package com.wisteria.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One row of the catalog manifest ({@code products.json}). Style attributes
 * are NOT supplied here — Claude extracts them at index time.
 *
 * {@code productId} is optional. When absent, a STABLE id is derived from the
 * image path (falling back to the name) so that re-indexing the same product
 * upserts the existing row instead of inserting a duplicate.
 */
public record ProductEntry(
        @JsonProperty("product_id") UUID productId,
        @JsonProperty("name")       String name,
        @JsonProperty("category")   String category,
        @JsonProperty("image_path") String imagePath
) {
    public UUID idOrDerived() {
        if (productId != null) {
            return productId;
        }
        String seed = (imagePath != null && !imagePath.isBlank()) ? imagePath : name;
        return UUID.nameUUIDFromBytes(("wisteria:" + seed).getBytes(StandardCharsets.UTF_8));
    }
}
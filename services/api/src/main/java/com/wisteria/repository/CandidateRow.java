package com.wisteria.repository;

import com.wisteria.model.StyleAttributes;

import java.util.UUID;

/**
 * One ANN candidate joined with its style attributes (LLD v2.1 Day 3/4).
 * {@code cosineDistance} is the pgvector {@code <=>} distance in [0,2].
 */
public record CandidateRow(
        UUID productId,
        String name,
        String imagePath,
        String furnitureCategory,
        String finish,
        String material,
        String silhouette,
        String era,
        String palette,
        String mood,
        double cosineDistance
) {
    public StyleAttributes attributes() {
        return new StyleAttributes(furnitureCategory, finish, material, silhouette, era, palette, mood, null);
    }
}
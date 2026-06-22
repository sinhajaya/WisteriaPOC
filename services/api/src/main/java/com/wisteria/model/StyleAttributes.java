package com.wisteria.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Style DNA extracted from an image by Claude Vision (LLD v2.1).
 *
 * {@code furnitureCategory} is the KIND of furniture (coarse, closed vocab) and
 * drives the primary matching preference; the remaining six are fine-grained
 * style attributes. Values come from closed enums so exact-match attribute
 * overlap scoring is meaningful. {@code rawJson} keeps the full tool response.
 */
public record StyleAttributes(
        String furnitureCategory,  // seating | table | storage | bed | lighting | rug | decor | other
        String finish,
        String material,
        String silhouette,
        String era,
        String palette,
        String mood,
        String rawJson
) {
    /** Scored fields in canonical (display & priority) order. */
    public static final List<String> FIELDS =
            List.of("category", "palette", "material", "finish", "silhouette", "era", "mood");

    /** Field name → value (nulls preserved), excluding rawJson. Ordered by priority. */
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("category", furnitureCategory);
        m.put("palette", palette);
        m.put("material", material);
        m.put("finish", finish);
        m.put("silhouette", silhouette);
        m.put("era", era);
        m.put("mood", mood);
        return m;
    }
}
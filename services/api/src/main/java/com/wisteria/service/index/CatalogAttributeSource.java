package com.wisteria.service.index;

import com.wisteria.model.ProductEntry;
import com.wisteria.model.StyleAttributes;

/**
 * Where the offline indexer gets each product's style attributes.
 *
 * Decouples the INDEX path from the live {@code StyleExtractor} so the catalog
 * can be indexed from pre-computed Claude teacher labels (zero API cost, no VLM
 * dependency) without affecting the QUERY path, which keeps calling the VLM.
 *
 * Selected by {@code wisteria.catalog.attribute-source}:
 *   extractor (default) → {@link ExtractorAttributeSource} (live VLM/Claude)
 *   labels              → {@link LabelsAttributeSource} (labeled.jsonl)
 */
public interface CatalogAttributeSource {

    /** Style attributes for a catalog product. {@code jpegBase64} is the normalized image (extractor source uses it; labels source ignores it). */
    StyleAttributes attributesFor(ProductEntry entry, String jpegBase64);
}

package com.wisteria.service.index;

import com.wisteria.model.ProductEntry;
import com.wisteria.model.StyleAttributes;
import com.wisteria.service.vision.StyleExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default attribute source (LLD v2.1): call the live {@link StyleExtractor}
 * (VLM or Claude per {@code wisteria.vision.provider}) for each product image.
 *
 * Retries once on malformed output then propagates, so the indexer skips and
 * counts the single bad product without aborting the batch.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "wisteria.catalog.attribute-source",
        havingValue = "extractor", matchIfMissing = true)
public class ExtractorAttributeSource implements CatalogAttributeSource {

    private final StyleExtractor extractor;

    public ExtractorAttributeSource(StyleExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public StyleAttributes attributesFor(ProductEntry entry, String jpegBase64) {
        try {
            return extractor.extract(jpegBase64);
        } catch (Exception first) {
            log.debug("Vision extract failed, retrying once: {}", first.getMessage());
            return extractor.extract(jpegBase64);
        }
    }
}

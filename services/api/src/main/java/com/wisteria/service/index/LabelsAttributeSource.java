package com.wisteria.service.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wisteria.model.ProductEntry;
import com.wisteria.model.StyleAttributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Index from the Claude teacher labels (training/data/labeled.jsonl) instead of
 * calling the live extractor — zero API cost, no VLM/Colab dependency. CLIP
 * embeddings still come from the clip-server; only the style attributes change.
 *
 * Active when {@code wisteria.catalog.attribute-source=labels}. Labels are
 * loaded once at startup into a basename→attributes map. Each row is
 * {@code {"image": "../catalog/images/foo.jpg", "attributes": {category, finish,
 * material, silhouette, era, palette, mood}, ...}}; the lookup keys on the image
 * basename so it matches products.json's {@code image_path}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "wisteria.catalog.attribute-source", havingValue = "labels")
public class LabelsAttributeSource implements CatalogAttributeSource {

    private final Map<String, StyleAttributes> byBasename = new HashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public LabelsAttributeSource(ResourceLoader resourceLoader,
                                 @Value("${wisteria.catalog.labels-file}") String labelsFile) {
        load(resourceLoader, labelsFile);
    }

    private void load(ResourceLoader resourceLoader, String labelsFile) {
        Resource resource = resourceLoader.getResource(labelsFile);
        if (!resource.exists()) {
            throw new IllegalStateException("teacher labels not found: " + labelsFile
                    + " — copy training/data/labeled.jsonl into the catalog mount, or set "
                    + "wisteria.catalog.attribute-source=extractor");
        }
        try (var reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty()) {
                    continue;
                }
                JsonNode row = mapper.readTree(line);
                String image = row.path("image").asText(null);
                JsonNode attrs = row.get("attributes");
                if (image == null || attrs == null) {
                    continue;
                }
                byBasename.put(basename(image), toStyleAttributes(attrs));
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read teacher labels " + labelsFile + ": " + e.getMessage(), e);
        }
        log.info("Loaded {} teacher labels from {} (attribute-source=labels)", byBasename.size(), labelsFile);
    }

    @Override
    public StyleAttributes attributesFor(ProductEntry entry, String jpegBase64) {
        StyleAttributes attrs = byBasename.get(basename(entry.imagePath()));
        if (attrs == null) {
            throw new IllegalStateException("no teacher label for image " + entry.imagePath()
                    + " — re-run training/label_with_claude.py to cover it");
        }
        return attrs;
    }

    /** Map the label JSON (same closed vocab as the extractor tool) onto StyleAttributes. */
    private StyleAttributes toStyleAttributes(JsonNode in) throws Exception {
        return new StyleAttributes(
                text(in, "category"),     // → furnitureCategory
                text(in, "finish"),
                text(in, "material"),
                text(in, "silhouette"),
                text(in, "era"),
                text(in, "palette"),
                text(in, "mood"),
                mapper.writeValueAsString(in));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String basename(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }
}
package com.wisteria.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves catalog product images at {@code /catalog/**} from the same directory
 * the indexer reads ({@code wisteria.catalog.image-dir}). Result
 * {@code image_url}s are built as {@code /catalog/<image_path>} so the React UI
 * can render them directly (proxied via Vite in dev).
 */
@Configuration
public class CatalogImageConfig implements WebMvcConfigurer {

    private final String imageDir;

    public CatalogImageConfig(@Value("${wisteria.catalog.image-dir}") String imageDir) {
        this.imageDir = imageDir.endsWith("/") ? imageDir : imageDir + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/catalog/**").addResourceLocations(imageDir);
    }
}
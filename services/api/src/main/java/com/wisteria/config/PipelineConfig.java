package com.wisteria.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real-pipeline beans (LLD v2.1). Active for every profile EXCEPT "stub",
 * which runs the API layer with zero infrastructure.
 */
@Configuration
public class PipelineConfig {

    /** Anthropic Vision + Haiku client. API key from ANTHROPIC_API_KEY. */
    @Bean
    public AnthropicClient anthropicClient(@Value("${anthropic.api-key:}") String apiKey) {
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    /**
     * HTTP client for the Python CLIP server (:5001). Read timeout is generous
     * because CPU CLIP embedding is 3–6 s; the query path bounds total latency
     * separately via CompletableFuture.orTimeout.
     */
    @Bean
    public RestClient clipRestClient(@Value("${wisteria.clip.url}") String clipUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder()
                .baseUrl(clipUrl)
                .requestFactory(factory)
                .build();
    }

    /** HTTP client for the self-hosted VLM server (:5002), used when wisteria.vision.provider=vlm. */
    @Bean
    public RestClient vlmRestClient(@Value("${wisteria.vlm.url}") String vlmUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder()
                .baseUrl(vlmUrl)
                .requestFactory(factory)
                .build();
    }

    /** One virtual thread per task — used to fan CLIP + vision extraction out in parallel. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService virtualExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
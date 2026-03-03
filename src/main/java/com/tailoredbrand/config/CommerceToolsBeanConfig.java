package com.tailoredbrand.config;

import com.tailoredbrand.commerce.CommerceToolsSettings;
import com.tailoredbrand.commerce.CommerceToolsTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Objects;

/**
 * Exposes CommerceTools infrastructure objects as Spring beans so they can be
 * injected into controllers and services.
 *
 * <p>The same {@code pipeline.yaml} that drives the Beam pipeline is the single
 * source of truth for all connection settings.  No duplication is needed.</p>
 */
@Configuration
public class CommerceToolsBeanConfig {

    /** Loads the application config from {@code config/pipeline.yaml} on the classpath. */
    @Bean
    public AppConfig appConfig() {
        return YamlConfigLoader.load("config/pipeline.yaml");
    }

    /** Immutable settings record used by every CT client / service. */
    @Bean
    public CommerceToolsSettings commerceToolsSettings(AppConfig appConfig) {
        AppConfig.CommerceApi cfg = Objects.requireNonNull(
                appConfig.getCommerce(), "commerce config block is required in pipeline.yaml");

        return CommerceToolsSettings.builder()
                .authUrl(Objects.requireNonNull(cfg.getAuthUrl(),             "commerce.authUrl"))
                .clientCredentials(Objects.requireNonNull(cfg.getClientCredentials(), "commerce.clientCredentials"))
                .scope(Objects.requireNonNull(cfg.getScope(),                 "commerce.scope"))
                .apiUrl(Objects.requireNonNull(cfg.getApiUrl(),               "commerce.apiUrl"))
                .projectKey(Objects.requireNonNull(cfg.getProjectKey(),       "commerce.projectKey"))
                .productTypeKey(Objects.requireNonNull(cfg.getProductTypeKey(), "commerce.productTypeKey"))
                .secondaryProductTypeKey(cfg.getSecondaryProductTypeKey())
                .primaryProductTypeDivisions(cfg.getPrimaryProductTypeDivisions())
                .connectTimeoutMs(cfg.getConnectTimeoutMs() != null ? cfg.getConnectTimeoutMs() : 5_000)
                .readTimeoutMs(cfg.getReadTimeoutMs()       != null ? cfg.getReadTimeoutMs()     : 15_000)
                .maxRetries(cfg.getMaxRetries()             != null ? cfg.getMaxRetries()         : 3)
                .backoffMs(cfg.getBackoffMs()               != null ? cfg.getBackoffMs()          : 500L)
                .build();
    }

    /** Token service shared across all CT API callers in the Spring context. */
    @Bean
    public CommerceToolsTokenService commerceToolsTokenService(CommerceToolsSettings settings) {
        return new CommerceToolsTokenService(settings);
    }
}

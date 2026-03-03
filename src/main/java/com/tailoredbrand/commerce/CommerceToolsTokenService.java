package com.tailoredbrand.commerce;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

/**
 * Fetches and caches a CommerceTools OAuth2 client-credentials token.
 *
 * <p>The underlying {@link WebClient} is {@code transient} so Beam can serialize
 * this service as part of a {@link org.apache.beam.sdk.transforms.DoFn} field;
 * it is lazily re-created on first use after deserialization.</p>
 *
 * <p>Token refresh is double-checked and {@code synchronized} to avoid stampedes
 * when multiple threads share one instance inside the same JVM worker.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class CommerceToolsTokenService implements Serializable {

    private final CommerceToolsSettings settings;

    private transient volatile String cachedToken;
    private transient volatile Instant tokenExpiry;
    private transient volatile WebClient authWebClient;

    // ── Public API ──────────────────────────────────────────────────────────

    /** Returns a valid Bearer token, refreshing it when it is about to expire. */
    public String getBearerToken() {
        if (isTokenValid()) {
            return cachedToken;
        }
        return refreshToken();
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private boolean isTokenValid() {
        return cachedToken != null
                && tokenExpiry != null
                && Instant.now().isBefore(tokenExpiry.minusSeconds(60));
    }

    private synchronized String refreshToken() {
        if (isTokenValid()) {
            return cachedToken;
        }

        log.info("[CT AUTH] ► Requesting OAuth token | authUrl={} | scope={}",
                settings.getAuthUrl(), settings.getScope());

        TokenResponse response = buildWebClient()
                .post()
                .uri("/oauth/token")
                .header("Authorization", "Basic " + settings.getClientCredentials())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .bodyValue("grant_type=client_credentials&scope=" + settings.getScope())
                .retrieve()
                .bodyToMono(TokenResponse.class)
                .block(Duration.ofMillis(settings.getReadTimeoutMs()));

        if (response == null || response.accessToken() == null) {
            log.error("[CT AUTH] ✗ Token response was null or missing access_token");
            throw new IllegalStateException("CommerceTools returned a null token response");
        }

        cachedToken  = response.accessToken();
        tokenExpiry  = Instant.now().plusSeconds(response.expiresIn());
        log.info("[CT AUTH] ✓ Token acquired | type={} | scope={} | expiresIn={}s | expiresAt={}",
                response.tokenType(), response.scope(), response.expiresIn(), tokenExpiry);
        return cachedToken;
    }

    /** Lazy init so the WebClient survives Java serialization / Beam worker restore. */
    private WebClient buildWebClient() {
        if (authWebClient == null) {
            log.info("[CT AUTH] Initializing auth WebClient → {}", settings.getAuthUrl());

            HttpClient nettyClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, settings.getConnectTimeoutMs())
                    .responseTimeout(Duration.ofMillis(settings.getReadTimeoutMs()));

            authWebClient = WebClient.builder()
                    .baseUrl(settings.getAuthUrl())
                    .clientConnector(new ReactorClientHttpConnector(nettyClient))
                    .build();

            log.info("[CT AUTH] Auth WebClient ready (connectTimeout={}ms readTimeout={}ms)",
                    settings.getConnectTimeoutMs(), settings.getReadTimeoutMs());
        }
        return authWebClient;
    }

    // ── Token response model ────────────────────────────────────────────────

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type")   String tokenType,
            @JsonProperty("expires_in")   long expiresIn,
            String scope
    ) {
    }
}

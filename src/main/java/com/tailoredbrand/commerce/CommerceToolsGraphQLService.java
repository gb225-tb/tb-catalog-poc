package com.tailoredbrand.commerce;

import com.tailoredbrand.commerce.CommerceToolsGraphQLModels.*;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Spring service that executes CommerceTools GraphQL queries.
 *
 * <p>Endpoint: {@code POST /{projectKey}/graphql}</p>
 *
 * <p>This class is intentionally limited to a single, fully-expanded
 * <em>product-by-key</em> query matching the shape required by the frontend.
 * Additional queries can be added as new public methods following the same pattern.</p>
 */
@Service
@Slf4j
public class CommerceToolsGraphQLService {

    // ── GraphQL query template ────────────────────────────────────────────────

    /**
     * Fully-expanded product query.  The single {@code %s} placeholder is replaced with
     * the product key at runtime via {@link String#formatted}.
     *
     * <p>The locale is hard-coded to {@code en-US} to match the existing REST API
     * payload convention; make this a parameter if multi-locale support is needed.</p>
     */
    private static final String PRODUCT_QUERY_TEMPLATE = """
            query {
              product(key: "%s") {
                id
                key
                version
                createdAt
                productType {
                  id
                }
                masterData {
                  published
                  hasStagedChanges
                  current {
                    name(locale: "en-US")
                    description(locale: "en-US")
                    slug(locale: "en-US")
                    categories {
                      id
                    }
                    attributesRaw {
                      name
                      value
                    }
                    masterVariant {
                      id
                      sku
                      prices {
                        value {
                          centAmount
                          currencyCode
                        }
                      }
                      images {
                        url
                      }
                      attributesRaw {
                        name
                        value
                      }
                    }
                    variants {
                      id
                      sku
                      attributesRaw {
                        name
                        value
                      }
                    }
                  }
                  staged {
                    name(locale: "en-US")
                    description(locale: "en-US")
                    slug(locale: "en-US")
                    attributesRaw {
                      name
                      value
                    }
                  }
                }
              }
            }
            """;

    // ── Fields ────────────────────────────────────────────────────────────────

    private final CommerceToolsSettings    settings;
    private final CommerceToolsTokenService tokenService;
    private final WebClient                webClient;

    public CommerceToolsGraphQLService(CommerceToolsSettings settings,
                                       CommerceToolsTokenService tokenService) {
        this.settings     = settings;
        this.tokenService = tokenService;
        this.webClient    = buildWebClient();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetches a single product from CommerceTools by its {@code key}.
     *
     * @param  key the product key (e.g. {@code "TMW_17FM"})
     * @return the {@link CTProduct} if found, or {@code null} when CT returns
     *         {@code "product": null} (i.e. no product with that key exists)
     * @throws CommerceToolsGraphQLException if CT returns GraphQL errors or an HTTP error
     */
    public CTProduct getProduct(String key) {
        log.info("[CT GRAPHQL] ► GET product | key={} | endpoint={}/{}/graphql",
                key, settings.getApiUrl(), settings.getProjectKey());

        String query = PRODUCT_QUERY_TEMPLATE.formatted(escapeGraphQLString(key));

        GraphQLResponse<ProductQueryData> response;
        try {
            response = webClient
                    .post()
                    .uri("/{project}/graphql", settings.getProjectKey())
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("query", query, "variables", Map.of()))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<GraphQLResponse<ProductQueryData>>() {})
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
        } catch (WebClientResponseException ex) {
            log.error("[CT GRAPHQL] ✗ HTTP {} | key={} | body={}",
                    ex.getStatusCode().value(), key, ex.getResponseBodyAsString());
            throw new CommerceToolsGraphQLException(
                    "CT GraphQL HTTP " + ex.getStatusCode().value() + " for key=" + key,
                    ex.getStatusCode().value(), ex);
        }

        if (response == null) {
            log.warn("[CT GRAPHQL] ✗ Null response | key={}", key);
            throw new CommerceToolsGraphQLException("Empty response from CT GraphQL for key=" + key, 502, null);
        }

        if (response.hasErrors()) {
            String firstMessage = response.errors().get(0).message();
            log.error("[CT GRAPHQL] ✗ GraphQL errors | key={} | first='{}'", key, firstMessage);
            throw new CommerceToolsGraphQLException("CT GraphQL error: " + firstMessage,
                    HttpStatus.BAD_GATEWAY.value(), null);
        }

        CTProduct product = response.data() != null ? response.data().product() : null;
        if (product == null) {
            log.info("[CT GRAPHQL] → Product not found | key={}", key);
            return null;
        }

        log.info("[CT GRAPHQL] ✓ Found product | key={} | id={} | version={}",
                product.key(), product.id(), product.version());
        return product;
    }

    // ── Pass-through: caller supplies the full GraphQL payload ───────────────

    /**
     * Proxies an arbitrary GraphQL request to CommerceTools and returns the raw
     * response as a {@code Map} (preserving whatever shape CT returns, including
     * any {@code errors} array).
     *
     * <p>The Bearer token is injected automatically — the caller does not need
     * CT credentials.</p>
     *
     * @param  query     the GraphQL query / mutation string (required)
     * @param  variables optional variables map; {@code null} is treated as empty
     * @return raw CT GraphQL response ({@code data} + optional {@code errors})
     * @throws CommerceToolsGraphQLException on HTTP-level errors (4xx / 5xx from CT)
     */
    public Map<String, Object> execute(String query, Map<String, Object> variables) {
        Objects.requireNonNull(query, "GraphQL query must not be null");

        log.info("[CT GRAPHQL] ► PROXY execute | endpoint={}/{}/graphql",
                settings.getApiUrl(), settings.getProjectKey());

        Map<String, Object> body = Map.of(
                "query",     query,
                "variables", variables != null ? variables : Map.of()
        );

        try {
            Map<String, Object> response = webClient
                    .post()
                    .uri("/{project}/graphql", settings.getProjectKey())
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));

            if (response == null) {
                throw new CommerceToolsGraphQLException("Empty response from CT GraphQL", 502, null);
            }

            log.info("[CT GRAPHQL] ✓ PROXY execute complete | hasErrors={}",
                    response.containsKey("errors"));
            return response;

        } catch (WebClientResponseException ex) {
            log.error("[CT GRAPHQL] ✗ PROXY HTTP {} | body={}",
                    ex.getStatusCode().value(), ex.getResponseBodyAsString());
            throw new CommerceToolsGraphQLException(
                    "CT GraphQL HTTP " + ex.getStatusCode().value(),
                    ex.getStatusCode().value(), ex);
        }
    }

    // ── Checked exception ────────────────────────────────────────────────────

    /** Signals a non-retryable error from the CommerceTools GraphQL endpoint. */
    public static class CommerceToolsGraphQLException extends RuntimeException {
        private final int httpStatus;

        public CommerceToolsGraphQLException(String message, int httpStatus, Throwable cause) {
            super(message, cause);
            this.httpStatus = httpStatus;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private WebClient buildWebClient() {
        HttpClient nettyClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, settings.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(settings.getReadTimeoutMs()));

        return WebClient.builder()
                .baseUrl(settings.getApiUrl())
                .clientConnector(new ReactorClientHttpConnector(nettyClient))
                .build();
    }

    /** Escapes a product key so it is safe inside a GraphQL string literal. */
    private static String escapeGraphQLString(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

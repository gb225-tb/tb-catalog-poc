package com.tailoredbrand.importer.productdelete;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tailoredbrand.commerce.CommerceToolsSettings;
import com.tailoredbrand.commerce.CommerceToolsTokenService;
import com.tailoredbrand.importer.ImportResult;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deletes Products from CommerceTools based on a key-only CSV.
 *
 * <h3>Per-product logic</h3>
 * <ol>
 *   <li>Parse the CSV — each non-header, non-blank line is a product {@code key}.</li>
 *   <li>{@code GET /{project}/products/key={key}} — if not found, record as "skip"
 *       (already deleted or never existed).</li>
 *   <li>If the product is <em>published</em>, first
 *       {@code POST /{project}/products/{id}} with an {@code unpublish} action to
 *       obtain a fresh version number.</li>
 *   <li>{@code DELETE /{project}/products/{id}?version={version}} — delete and
 *       record a "delete" result.</li>
 * </ol>
 */
@Service
@Slf4j
public class ProductDeleteImportService {

    private static final String LOG = "[PRODUCT DELETE]";

    private final CommerceToolsSettings     settings;
    private final CommerceToolsTokenService tokenService;
    private final WebClient                 webClient;

    public ProductDeleteImportService(CommerceToolsSettings settings,
                                      CommerceToolsTokenService tokenService) {
        this.settings     = settings;
        this.tokenService = tokenService;
        this.webClient    = buildWebClient();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public List<ImportResult> deleteProducts(InputStream csvStream) throws IOException {
        List<String> keys = parseCsv(csvStream);
        log.info("{} Processing {} product key(s) for deletion", LOG, keys.size());

        List<ImportResult> results = new ArrayList<>();
        for (String key : keys) {
            results.add(processKey(key));
        }
        return results;
    }

    // ── CSV parsing ───────────────────────────────────────────────────────────

    /** Reads a single-column CSV (header = {@code key}), returns each non-blank value. */
    private List<String> parseCsv(InputStream csvStream) throws IOException {
        List<String> keys = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                String key = line.trim().split(",", -1)[0].trim();
                if (!key.isBlank()) keys.add(key);
            }
        }
        return keys;
    }

    // ── Per-key processing ────────────────────────────────────────────────────

    private ImportResult processKey(String key) {
        log.info("{} ► Processing | key={}", LOG, key);

        try {
            ProductResponse product = fetchProduct(key);
            if (product == null) {
                log.info("{} ✓ skip | product not found | key={}", LOG, key);
                return ImportResult.skipped(key);
            }

            long version = product.version();

            if (Boolean.TRUE.equals(product.masterData().published())) {
                log.info("{} ► Unpublishing before delete | key={}", LOG, key);
                version = unpublish(product.id(), version);
            }

            deleteProduct(product.id(), version);
            log.info("{} ✓ deleted | key={}", LOG, key);
            return ImportResult.deleted(key);

        } catch (WebClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            log.error("{} ✗ CT error | key={} | status={} | body={}", LOG, key, ex.getStatusCode().value(), body);
            return ImportResult.failure(key, ex.getStatusCode().value(), body);
        } catch (Exception ex) {
            log.error("{} ✗ unexpected | key={}", LOG, key, ex);
            return ImportResult.failure(key, 0, ex.getMessage());
        }
    }

    // ── CT API calls ──────────────────────────────────────────────────────────

    /** Returns the product, or {@code null} if it does not exist (404). */
    private ProductResponse fetchProduct(String key) {
        try {
            return webClient.get()
                    .uri("/{project}/products/key={key}", settings.getProjectKey(), key)
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .retrieve()
                    .bodyToMono(ProductResponse.class)
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
        } catch (WebClientResponseException.NotFound ignored) {
            return null;
        }
    }

    /**
     * Sends an {@code unpublish} update action and returns the new version number
     * from the CT response.
     */
    private long unpublish(String id, long currentVersion) {
        Map<String, Object> body = Map.of(
                "version", currentVersion,
                "actions", List.of(Map.of("action", "unpublish"))
        );
        ProductResponse updated = webClient.post()
                .uri("/{project}/products/{id}", settings.getProjectKey(), id)
                .header("Authorization", "Bearer " + tokenService.getBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ProductResponse.class)
                .block(Duration.ofMillis(settings.getReadTimeoutMs()));
        return updated != null ? updated.version() : currentVersion;
    }

    private void deleteProduct(String id, long version) {
        webClient.delete()
                .uri("/{project}/products/{id}?version={v}",
                        settings.getProjectKey(), id, version)
                .header("Authorization", "Bearer " + tokenService.getBearerToken())
                .retrieve()
                .bodyToMono(Void.class)
                .block(Duration.ofMillis(settings.getReadTimeoutMs()));
    }

    // ── WebClient factory ─────────────────────────────────────────────────────

    private WebClient buildWebClient() {
        HttpClient nettyClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, settings.getConnectTimeoutMs())
                .responseTimeout(Duration.ofMillis(settings.getReadTimeoutMs()));
        return WebClient.builder()
                .baseUrl(settings.getApiUrl())
                .clientConnector(new ReactorClientHttpConnector(nettyClient))
                .build();
    }

    // ── CT response models (internal) ─────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProductResponse(String id, long version, MasterData masterData) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MasterData(Boolean published) {}
}

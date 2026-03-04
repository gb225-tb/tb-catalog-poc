package com.tailoredbrand.importer.productimage;

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

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.*;

/**
 * Adds images to existing product variants via the CT Products update API.
 *
 * <p>The CT product response is consumed as a raw {@code Map} to avoid Jackson
 * reflection issues with private inner record types.</p>
 *
 * <h3>Per-product logic</h3>
 * <ol>
 *   <li>{@code GET /{project}/products/key={key}} — fetch current staged version.</li>
 *   <li>Collect existing image URLs from all staged variants to skip duplicates.</li>
 *   <li>Build one {@code addExternalImage} action per new image URL.</li>
 *   <li>{@code POST /{project}/products/{id}} with all actions in a single call.</li>
 * </ol>
 *
 * <p>Images are applied to the <em>staged</em> version; publish separately if needed.</p>
 */
@Service
@Slf4j
public class ProductImageImportService {

    private static final String LOG = "[IMAGE IMPORT]";

    private final CommerceToolsSettings     settings;
    private final CommerceToolsTokenService tokenService;
    private final ProductImageCsvParser     parser;
    private final WebClient                 webClient;

    public ProductImageImportService(CommerceToolsSettings settings,
                                     CommerceToolsTokenService tokenService,
                                     ProductImageCsvParser parser) {
        this.settings     = settings;
        this.tokenService = tokenService;
        this.parser       = parser;
        this.webClient    = buildWebClient();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public List<ImportResult> importImages(InputStream csvStream) throws IOException {
        List<ProductImageGroup> groups = parser.parse(csvStream);
        log.info("{} Processing {} product group(s)", LOG, groups.size());

        List<ImportResult> results = new ArrayList<>();
        for (ProductImageGroup group : groups) {
            results.add(processGroup(group));
        }
        return results;
    }

    // ── Per-product processing ────────────────────────────────────────────────

    @SuppressWarnings("rawtypes")
    private ImportResult processGroup(ProductImageGroup group) {
        String productKey = group.productKey();
        log.info("{} ► Processing | productKey={} | images={}", LOG, productKey, group.imageRows().size());

        try {
            Map productMap = fetchProductRaw(productKey);
            if (productMap == null) {
                log.warn("{} ✗ product not found | productKey={}", LOG, productKey);
                return ImportResult.failure(productKey, 404, "Product not found: " + productKey);
            }

            String productId = (String) productMap.get("id");
            long   version   = ((Number) productMap.get("version")).longValue();

            Set<String> existingUrls = collectExistingImageUrls(productMap);
            List<Map<String, Object>> actions = new ArrayList<>();

            for (ProductImageRecord row : group.imageRows()) {
                if (row.imageUrl() == null) continue;
                if (existingUrls.contains(row.imageUrl())) {
                    log.info("{} ⚠ skip duplicate | sku={} url={}", LOG, row.variantSku(), row.imageUrl());
                    continue;
                }
                actions.add(buildAddImageAction(row));
                existingUrls.add(row.imageUrl());
            }

            if (actions.isEmpty()) {
                log.info("{} ✓ skip | all images already present | productKey={}", LOG, productKey);
                return ImportResult.skipped(productKey);
            }

            updateProduct(productId, version, actions);
            log.info("{} ✓ updated | productKey={} | added {} image(s)", LOG, productKey, actions.size());
            return ImportResult.updated(productKey);

        } catch (WebClientResponseException ex) {
            log.error("{} ✗ CT error | productKey={} | status={} | body={}",
                    LOG, productKey, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return ImportResult.failure(productKey, ex.getStatusCode().value(), ex.getResponseBodyAsString());
        } catch (Exception ex) {
            log.error("{} ✗ unexpected | productKey={}", LOG, productKey, ex);
            return ImportResult.failure(productKey, 0, ex.getMessage());
        }
    }

    // ── Action builders ───────────────────────────────────────────────────────

    private Map<String, Object> buildAddImageAction(ProductImageRecord row) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("url", row.imageUrl());
        if (row.imageLabel() != null) image.put("label", row.imageLabel());
        if (row.dimensionW() != null && row.dimensionH() != null) {
            image.put("dimensions", Map.of(
                    "w", Integer.parseInt(row.dimensionW()),
                    "h", Integer.parseInt(row.dimensionH())
            ));
        }

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("action", "addExternalImage");
        if (row.variantSku() != null) action.put("sku", row.variantSku());
        action.put("image", image);
        return action;
    }

    // ── Raw-Map navigation ────────────────────────────────────────────────────

    /**
     * Collects all image URLs from the staged variants of the product response map.
     * Checks both {@code staged} and {@code current} versions to avoid re-adding
     * images that are already present in either.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Set<String> collectExistingImageUrls(Map productMap) {
        Set<String> urls = new HashSet<>();
        try {
            Map masterData = (Map) productMap.get("masterData");
            if (masterData == null) return urls;

            for (String version : new String[]{"staged", "current"}) {
                Map catalogData = (Map) masterData.get(version);
                if (catalogData == null) continue;

                List<Map> allVariants = new ArrayList<>();
                Map masterVariant = (Map) catalogData.get("masterVariant");
                if (masterVariant != null) allVariants.add(masterVariant);
                List<Map> variants = (List<Map>) catalogData.get("variants");
                if (variants != null) allVariants.addAll(variants);

                for (Map variant : allVariants) {
                    List<Map> images = (List<Map>) variant.get("images");
                    if (images == null) continue;
                    for (Map img : images) {
                        String url = (String) img.get("url");
                        if (url != null) urls.add(url);
                    }
                }
            }
        } catch (ClassCastException ex) {
            log.warn("{} Could not navigate product response for image dedup: {}", LOG, ex.getMessage());
        }
        return urls;
    }

    // ── CT API calls ──────────────────────────────────────────────────────────

    @SuppressWarnings("rawtypes")
    private Map fetchProductRaw(String key) {
        try {
            return webClient.get()
                    .uri("/{project}/products/key={key}", settings.getProjectKey(), key)
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
        } catch (WebClientResponseException.NotFound ignored) {
            return null;
        }
    }

    @SuppressWarnings("rawtypes")
    private void updateProduct(String id, long version, List<Map<String, Object>> actions) {
        webClient.post()
                .uri("/{project}/products/{id}", settings.getProjectKey(), id)
                .header("Authorization", "Bearer " + tokenService.getBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("version", version, "actions", actions))
                .retrieve()
                .bodyToMono(Map.class)
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
}

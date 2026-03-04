package com.tailoredbrand.importer.product;

import com.tailoredbrand.commerce.CommerceToolsSettings;
import com.tailoredbrand.commerce.CommerceToolsTokenService;
import com.tailoredbrand.importer.ImportResult;
import com.tailoredbrand.importer.product.ProductImportModels.*;
import com.tailoredbrand.importer.product.ProductTypeModels.*;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports products from an uploaded CSV into CommerceTools.
 *
 * <h3>Per-product logic</h3>
 * <ol>
 *   <li>Parse and group the CSV rows into {@link ProductImportGroup}s.</li>
 *   <li>Map each group to a {@link ProductImportDraft}.</li>
 *   <li>{@code GET /{project}/products/key={key}} — if the product already exists,
 *       skip it and record a {@code "skip"} result.</li>
 *   <li>If not found, {@code POST /{project}/products} — create and record
 *       a {@code "create"} result.</li>
 * </ol>
 *
 * <p>Uses the existing {@link CommerceToolsSettings} and
 * {@link CommerceToolsTokenService} Spring beans for connection details and
 * Bearer-token management.</p>
 */
@Service
@Slf4j
public class ProductImportService {

    private final CommerceToolsSettings      settings;
    private final CommerceToolsTokenService  tokenService;
    private final ProductImportCsvParser     parser;
    private final ProductImportMapper        mapper;
    private final WebClient                  webClient;

    public ProductImportService(CommerceToolsSettings settings,
                                CommerceToolsTokenService tokenService,
                                ProductImportCsvParser parser,
                                ProductImportMapper mapper) {
        this.settings     = settings;
        this.tokenService = tokenService;
        this.parser       = parser;
        this.mapper       = mapper;
        this.webClient    = buildWebClient();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Processes all products in the uploaded CSV stream and returns one
     * {@link ImportResult} per product.
     *
     * <h3>Pre-flight checks (auto-create missing CT resources)</h3>
     * <ol>
     *   <li><b>Product types</b> — created with lenum values derived from the CSV.</li>
     *   <li><b>Tax categories</b> — created with an empty rates list.</li>
     *   <li><b>Categories</b> — created from the semicolon-separated {@code categories}
     *       column; name and slug are derived from the key.</li>
     *   <li><b>Channels</b> — created with role {@code ProductDistribution}.</li>
     * </ol>
     * Each check is independent: a failure in one does not abort the others.
     */
    public List<ImportResult> importProducts(InputStream csvStream) throws IOException {
        List<ProductImportGroup> groups = parser.parse(csvStream);
        log.info("[PRODUCT IMPORT] Processing {} product group(s)", groups.size());

        runPreflightChecks(groups);

        List<ImportResult> results = new ArrayList<>();
        for (ProductImportGroup group : groups) {
            results.add(processGroup(group));
        }
        return results;
    }

    private void runPreflightChecks(List<ProductImportGroup> groups) {
        log.info("[PRODUCT IMPORT] ── Pre-flight checks ──────────────────────────");
        ensureProductTypesExist(groups);
        ensureTaxCategoriesExist(groups);
        ensureCategoriesExist(groups);
        ensureChannelsExist(groups);
        log.info("[PRODUCT IMPORT] ── Pre-flight complete ─────────────────────────");
    }

    // ── Pre-flight: product type creation ────────────────────────────────────

    /**
     * For every distinct {@code productType.key} referenced in the groups,
     * checks whether the type exists in CT.  If not, creates it with the
     * standard attribute set for that type key.
     *
     * <p>Lenum values for {@code color} and {@code size} are collected from the
     * CSV data so the type definition always covers the actual import payload.</p>
     */
    private void ensureProductTypesExist(List<ProductImportGroup> groups) {
        groups.stream()
                .map(g -> g.header().productTypeKey())
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .forEach(typeKey -> {
                    try {
                        ensureProductTypeExists(typeKey, groups);
                    } catch (Exception ex) {
                        log.error("[PRODUCT IMPORT] Pre-flight failed for productType key='{}': {}",
                                typeKey, ex.getMessage());
                    }
                });
    }

    private void ensureProductTypeExists(String typeKey, List<ProductImportGroup> groups) {
        // Check existence
        try {
            webClient.get()
                    .uri("/{project}/product-types/key={key}", settings.getProjectKey(), typeKey)
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .retrieve()
                    .bodyToMono(ProductTypeResponse.class)
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
            log.info("[PRODUCT IMPORT] ✓ product type '{}' already exists — skipping creation", typeKey);
            return;
        } catch (WebClientResponseException.NotFound ignored) {
            log.info("[PRODUCT IMPORT] product type '{}' not found — will create it", typeKey);
        }

        // Collect distinct lenum values from the CSV for this product type
        List<LEnumValue> colorValues = collectLEnumValues(groups, typeKey,
                ProductImportRecord::attributesColorEnGB, "en-GB");
        List<LEnumValue> sizeValues  = collectLEnumValues(groups, typeKey,
                ProductImportRecord::attributesSizeEnGB, "en-GB");

        // Build the product type draft
        ProductTypeDraft draft = new ProductTypeDraft(
                typeKey,
                typeKey,
                "Auto-created by import pipeline from uploaded CSV",
                List.of(
                        textAttribute("brand",       "Brand",       true),
                        lenumAttribute("color",      "Color",       colorValues),
                        lenumAttribute("size",       "Size",        sizeValues),
                        boolAttribute("new-arrival", "New Arrival")
                )
        );

        webClient.post()
                .uri("/{project}/product-types", settings.getProjectKey())
                .header("Authorization", "Bearer " + tokenService.getBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(draft)
                .retrieve()
                .bodyToMono(ProductTypeResponse.class)
                .block(Duration.ofMillis(settings.getReadTimeoutMs()));

        log.info("[PRODUCT IMPORT] ✓ product type '{}' created with {} color(s) and {} size(s)",
                typeKey, colorValues.size(), sizeValues.size());
    }

    // ── Pre-flight: tax category ──────────────────────────────────────────────

    /**
     * Collects all distinct {@code taxCategory.key} values from the CSV and
     * creates any that are missing in CT.
     * A tax category is created with an empty {@code rates} list — rates can
     * be added in the CT Merchant Center after import.
     */
    private void ensureTaxCategoriesExist(List<ProductImportGroup> groups) {
        groups.stream()
                .map(g -> g.header().taxCategoryKey())
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .forEach(key -> {
                    try {
                        if (ctResourceExists("tax-categories", key)) {
                            log.info("[PRODUCT IMPORT] ✓ tax-category '{}' already exists", key);
                            return;
                        }
                        Map<String, Object> draft = Map.of(
                                "key",   key,
                                "name",  capitalize(key),
                                "rates", List.of()
                        );
                        postResource("tax-categories", key, draft);
                        log.info("[PRODUCT IMPORT] ✓ tax-category '{}' created", key);
                    } catch (Exception ex) {
                        log.error("[PRODUCT IMPORT] Pre-flight failed for tax-category '{}': {}",
                                key, ex.getMessage());
                    }
                });
    }

    // ── Pre-flight: categories ────────────────────────────────────────────────

    /**
     * Collects all distinct category keys from the semicolon-separated
     * {@code categories} column and creates any that are missing.
     * Name and slug are derived from the key (e.g. {@code "home-decor"} →
     * name {@code "Home Decor"}, slug {@code "home-decor"}).
     */
    private void ensureCategoriesExist(List<ProductImportGroup> groups) {
        groups.stream()
                .map(g -> g.header().categories())
                .filter(v -> v != null && !v.isBlank())
                .flatMap(v -> java.util.Arrays.stream(v.split(";")))
                .map(String::trim)
                .filter(k -> !k.isBlank())
                .distinct()
                .forEach(key -> {
                    try {
                        if (ctResourceExists("categories", key)) {
                            log.info("[PRODUCT IMPORT] ✓ category '{}' already exists", key);
                            return;
                        }
                        String label = capitalize(key);
                        Map<String, Object> draft = Map.of(
                                "key",  key,
                                "name", Map.of("en-GB", label),
                                "slug", Map.of("en-GB", key)
                        );
                        postResource("categories", key, draft);
                        log.info("[PRODUCT IMPORT] ✓ category '{}' created", key);
                    } catch (Exception ex) {
                        log.error("[PRODUCT IMPORT] Pre-flight failed for category '{}': {}",
                                key, ex.getMessage());
                    }
                });
    }

    // ── Pre-flight: channels ──────────────────────────────────────────────────

    /**
     * Collects all distinct {@code variants.prices.channel.key} values and
     * creates any missing channels with role {@code ProductDistribution}.
     */
    private void ensureChannelsExist(List<ProductImportGroup> groups) {
        groups.stream()
                .flatMap(g -> g.allRows().stream())
                .map(ProductImportRecord::variantsPricesChannelKey)
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .forEach(key -> {
                    try {
                        if (ctResourceExists("channels", key)) {
                            log.info("[PRODUCT IMPORT] ✓ channel '{}' already exists", key);
                            return;
                        }
                        Map<String, Object> draft = Map.of(
                                "key",   key,
                                "roles", List.of("ProductDistribution")
                        );
                        postResource("channels", key, draft);
                        log.info("[PRODUCT IMPORT] ✓ channel '{}' created", key);
                    } catch (Exception ex) {
                        log.error("[PRODUCT IMPORT] Pre-flight failed for channel '{}': {}",
                                key, ex.getMessage());
                    }
                });
    }

    // ── Shared GET-check / POST helper ────────────────────────────────────────

    /**
     * Returns {@code true} if a CT resource of the given collection
     * (e.g. {@code "tax-categories"}, {@code "categories"}, {@code "channels"})
     * with the given key already exists.
     */
    private boolean ctResourceExists(String collection, String key) {
        try {
            webClient.get()
                    .uri("/{project}/{collection}/key={key}",
                            settings.getProjectKey(), collection, key)
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
            return true;
        } catch (WebClientResponseException.NotFound ignored) {
            return false;
        }
    }

    /**
     * POSTs a generic draft map to a CT collection endpoint and logs the result.
     */
    private void postResource(String collection, String key, Map<String, Object> draft) {
        webClient.post()
                .uri("/{project}/{collection}", settings.getProjectKey(), collection)
                .header("Authorization", "Bearer " + tokenService.getBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(draft)
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofMillis(settings.getReadTimeoutMs()));
    }

    // ── Lenum value collector ─────────────────────────────────────────────────

    @FunctionalInterface
    interface ValueExtractor {
        String extract(ProductImportRecord record);
    }

    /**
     * Scans all variant rows in the given groups for a specific lenum column,
     * de-duplicates the values, and maps each to an {@link LEnumValue} with a
     * capitalised label in the given locale.
     */
    private List<LEnumValue> collectLEnumValues(List<ProductImportGroup> groups,
                                                 String typeKey,
                                                 ValueExtractor extractor,
                                                 String locale) {
        // Use LinkedHashMap to preserve insertion order and deduplicate
        Map<String, LEnumValue> seen = new LinkedHashMap<>();

        groups.stream()
                .filter(g -> typeKey.equals(g.header().productTypeKey()))
                .flatMap(g -> g.allRows().stream())
                .map(extractor::extract)
                .filter(v -> v != null && !v.isBlank())
                .forEach(key -> seen.computeIfAbsent(key, k ->
                        new LEnumValue(k, Map.of(locale, capitalize(k)))));

        return new ArrayList<>(seen.values());
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('-', ' ').replace('_', ' ');
    }

    // ── Attribute definition builders ─────────────────────────────────────────

    private AttributeDefinitionDraft textAttribute(String name, String label, boolean isSearchable) {
        return new AttributeDefinitionDraft(
                AttributeType.text(), name, Map.of("en", label),
                false, "None", isSearchable, "SingleLine");
    }

    private AttributeDefinitionDraft boolAttribute(String name, String label) {
        return new AttributeDefinitionDraft(
                AttributeType.bool(), name, Map.of("en", label),
                false, "None", true, null);
    }

    private AttributeDefinitionDraft lenumAttribute(String name, String label,
                                                      List<LEnumValue> values) {
        // CT requires at least one lenum value; add a placeholder if CSV has none
        List<LEnumValue> vals = values.isEmpty()
                ? List.of(new LEnumValue("other", Map.of("en-GB", "Other")))
                : values;
        return new AttributeDefinitionDraft(
                AttributeType.lenum(vals), name, Map.of("en", label),
                false, "None", true, null);
    }

    // ── Per-product processing ────────────────────────────────────────────────

    private ImportResult processGroup(ProductImportGroup group) {
        String key = group.header().key();
        log.info("[PRODUCT IMPORT] ► Processing product | key={}", key);

        try {
            // Check for existing product
            if (productExists(key)) {
                log.info("[PRODUCT IMPORT] ✓ skip | product already exists | key={}", key);
                return ImportResult.skipped(key);
            }

            // Map and create
            ProductImportDraft draft = mapper.toProductDraft(group);
            createProduct(draft);
            log.info("[PRODUCT IMPORT] ✓ created | key={}", key);
            return ImportResult.created(key);

        } catch (WebClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            log.error("[PRODUCT IMPORT] ✗ CT error | key={} | status={} | body={}",
                    key, ex.getStatusCode().value(), body);
            return ImportResult.failure(key, ex.getStatusCode().value(), body);
        } catch (Exception ex) {
            log.error("[PRODUCT IMPORT] ✗ unexpected error | key={}", key, ex);
            return ImportResult.failure(key, 0, ex.getMessage());
        }
    }

    // ── CT API calls ──────────────────────────────────────────────────────────

    private boolean productExists(String key) {
        try {
            webClient.get()
                    .uri("/{project}/products/key={key}", settings.getProjectKey(), key)
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .retrieve()
                    .bodyToMono(ProductExistsResponse.class)
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
            return true;
        } catch (WebClientResponseException.NotFound ignored) {
            return false;
        }
    }

    private void createProduct(ProductImportDraft draft) {
        webClient.post()
                .uri("/{project}/products", settings.getProjectKey())
                .header("Authorization", "Bearer " + tokenService.getBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(draft)
                .retrieve()
                .bodyToMono(ProductExistsResponse.class)
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

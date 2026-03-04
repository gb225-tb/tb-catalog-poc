package com.tailoredbrand.importer.productprice;

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
 * Adds or updates prices on existing product variants via the CT Products update API.
 *
 * <h3>Pre-flight checks</h3>
 * <ul>
 *   <li><b>Channels</b> — any channel key in {@code variants.prices.channel.key} is
 *       created with the {@code ProductDistribution} role if missing.</li>
 *   <li><b>Custom types</b> — checks whether the referenced type supports
 *       {@code product-price}; if it exists for a different resource, a derived key
 *       {@code {key}-price} is created and mapped transparently.</li>
 * </ul>
 *
 * <h3>Per-product logic</h3>
 * <ol>
 *   <li>{@code GET /{project}/products/key={key}} — fetch current id, version, and
 *       existing price keys.</li>
 *   <li>For each price row:
 *       <ul>
 *         <li>price key already exists in the product → {@code changePrice} action</li>
 *         <li>price key is new → {@code addPrice} action</li>
 *       </ul>
 *   </li>
 *   <li>{@code POST /{project}/products/{id}} — all price actions in one call.</li>
 * </ol>
 *
 * <p>Updates apply to the <em>staged</em> version; publish separately if needed.</p>
 */
@Service
@Slf4j
public class ProductPriceImportService {

    private static final String LOG = "[PRICE IMPORT]";

    private final CommerceToolsSettings     settings;
    private final CommerceToolsTokenService tokenService;
    private final ProductPriceCsvParser     parser;
    private final WebClient                 webClient;

    public ProductPriceImportService(CommerceToolsSettings settings,
                                     CommerceToolsTokenService tokenService,
                                     ProductPriceCsvParser parser) {
        this.settings     = settings;
        this.tokenService = tokenService;
        this.parser       = parser;
        this.webClient    = buildWebClient();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public List<ImportResult> importPrices(InputStream csvStream) throws IOException {
        List<ProductPriceGroup> groups = parser.parse(csvStream);
        log.info("{} Processing {} product group(s)", LOG, groups.size());

        Map<String, String> customTypeKeyMap = runPreflightChecks(groups);

        List<ImportResult> results = new ArrayList<>();
        for (ProductPriceGroup group : groups) {
            results.add(processGroup(group, customTypeKeyMap));
        }
        return results;
    }

    // ── Pre-flight ────────────────────────────────────────────────────────────

    private Map<String, String> runPreflightChecks(List<ProductPriceGroup> groups) {
        log.info("{} ── Pre-flight checks ──────────────────────────", LOG);
        ensureChannelsExist(groups);
        Map<String, String> customTypeKeyMap = ensureCustomTypesExist(groups);
        log.info("{} ── Pre-flight complete ─────────────────────────", LOG);
        return customTypeKeyMap;
    }

    private void ensureChannelsExist(List<ProductPriceGroup> groups) {
        Set<String> channelKeys = new LinkedHashSet<>();
        groups.forEach(g -> g.priceRows().forEach(r -> {
            if (r.channelKey() != null) channelKeys.add(r.channelKey());
        }));

        channelKeys.forEach(key -> {
            try {
                if (ctResourceExists("channels", key)) {
                    log.info("{} ✓ channel '{}' already exists", LOG, key);
                    return;
                }
                postResource("channels", Map.of("key", key, "roles", List.of("ProductDistribution")));
                log.info("{} ✓ channel '{}' created", LOG, key);
            } catch (WebClientResponseException ex) {
                log.error("{} Pre-flight failed for channel '{}' | status={} | body={}",
                        LOG, key, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            } catch (Exception ex) {
                log.error("{} Pre-flight failed for channel '{}': {}", LOG, key, ex.getMessage());
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, String> ensureCustomTypesExist(List<ProductPriceGroup> groups) {
        Map<String, String> keyMap = new LinkedHashMap<>();

        Set<String> typeKeys = new LinkedHashSet<>();
        groups.forEach(g -> g.priceRows().forEach(r -> {
            if (r.customTypeKey() != null) typeKeys.add(r.customTypeKey());
        }));

        typeKeys.forEach(csvKey -> {
            try {
                List<String> existing = fetchTypeResourceTypeIds(csvKey);
                if (existing != null) {
                    if (existing.contains("product-price")) {
                        log.info("{} ✓ custom type '{}' exists and supports product-price", LOG, csvKey);
                        keyMap.put(csvKey, csvKey);
                    } else {
                        String derivedKey = csvKey + "-price";
                        keyMap.put(csvKey, derivedKey);
                        log.warn("{} ⚠ custom type '{}' is for {} (not product-price) → using '{}'",
                                LOG, csvKey, existing, derivedKey);
                        ensurePriceCustomType(derivedKey);
                    }
                    return;
                }
                createPriceCustomType(csvKey);
                keyMap.put(csvKey, csvKey);
                log.info("{} ✓ custom type '{}' created for product-price", LOG, csvKey);
            } catch (WebClientResponseException ex) {
                log.error("{} Pre-flight failed for custom type '{}' | status={} | body={}",
                        LOG, csvKey, ex.getStatusCode().value(), ex.getResponseBodyAsString());
                keyMap.put(csvKey, csvKey);
            } catch (Exception ex) {
                log.error("{} Pre-flight failed for custom type '{}': {}", LOG, csvKey, ex.getMessage());
                keyMap.put(csvKey, csvKey);
            }
        });

        return keyMap;
    }

    private void ensurePriceCustomType(String derivedKey) {
        List<String> existing = fetchTypeResourceTypeIds(derivedKey);
        if (existing != null) {
            log.info("{} ✓ derived custom type '{}' already exists", LOG, derivedKey);
            return;
        }
        createPriceCustomType(derivedKey);
        log.info("{} ✓ derived custom type '{}' created for product-price", LOG, derivedKey);
    }

    private void createPriceCustomType(String key) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("key",             key);
        draft.put("name",            Map.of("en", capitalize(key)));
        draft.put("description",     Map.of("en", "Product price custom fields"));
        draft.put("resourceTypeIds", List.of("product-price"));
        draft.put("fieldDefinitions", List.of(
                fieldDef("discounted",    "Discounted",   "Boolean"),
                fieldDef("banner-title",  "Banner Title", "LocalizedString")
        ));
        postResource("types", draft);
    }

    // ── Per-product processing ────────────────────────────────────────────────

    @SuppressWarnings("rawtypes")
    private ImportResult processGroup(ProductPriceGroup group, Map<String, String> customTypeKeyMap) {
        String productKey = group.productKey();
        log.info("{} ► Processing | productKey={} | prices={}", LOG, productKey, group.priceRows().size());

        try {
            Map productMap = fetchProductRaw(productKey);
            if (productMap == null) {
                log.warn("{} ✗ product not found | productKey={}", LOG, productKey);
                return ImportResult.failure(productKey, 404, "Product not found: " + productKey);
            }

            String productId = (String) productMap.get("id");
            long   version   = ((Number) productMap.get("version")).longValue();

            // Map existing price key → price id for detecting add-vs-change
            Map<String, String> existingPriceKeyToId = collectExistingPriceKeyToId(productMap);
            List<Map<String, Object>> actions = new ArrayList<>();

            for (ProductPriceRecord row : group.priceRows()) {
                String actualTypeKey = row.customTypeKey() != null
                        ? customTypeKeyMap.getOrDefault(row.customTypeKey(), row.customTypeKey())
                        : null;

                String existingPriceId = row.priceKey() != null
                        ? existingPriceKeyToId.get(row.priceKey()) : null;

                if (existingPriceId != null) {
                    actions.add(buildChangePriceAction(existingPriceId, row, actualTypeKey));
                    log.info("{} ► changePrice | priceKey={}", LOG, row.priceKey());
                } else {
                    actions.add(buildAddPriceAction(row, actualTypeKey));
                    log.info("{} ► addPrice | priceKey={}", LOG, row.priceKey());
                }
            }

            if (actions.isEmpty()) {
                return ImportResult.skipped(productKey);
            }

            updateProduct(productId, version, actions);
            log.info("{} ✓ updated | productKey={} | {} price action(s)", LOG, productKey, actions.size());
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

    private Map<String, Object> buildAddPriceAction(ProductPriceRecord row, String actualTypeKey) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("action", "addPrice");
        if (row.variantSku() != null) action.put("sku", row.variantSku());
        action.put("price", buildPriceDraft(row, actualTypeKey));
        return action;
    }

    private Map<String, Object> buildChangePriceAction(String priceId, ProductPriceRecord row,
                                                        String actualTypeKey) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("action",  "changePrice");
        action.put("priceId", priceId);
        action.put("price",   buildPriceDraft(row, actualTypeKey));
        return action;
    }

    private Map<String, Object> buildPriceDraft(ProductPriceRecord row, String actualTypeKey) {
        Map<String, Object> draft = new LinkedHashMap<>();
        if (row.priceKey() != null) draft.put("key", row.priceKey());

        // value — centPrecision vs highPrecision
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("currencyCode", row.currencyCode());
        String priceType = row.type() != null ? row.type() : "centPrecision";
        value.put("type", priceType);
        if ("highPrecision".equals(priceType)) {
            if (row.preciseAmount()  != null) value.put("preciseAmount",  Long.parseLong(row.preciseAmount()));
            if (row.fractionDigits() != null) value.put("fractionDigits", Integer.parseInt(row.fractionDigits()));
        } else {
            if (row.centAmount()     != null) value.put("centAmount",     Long.parseLong(row.centAmount()));
            if (row.fractionDigits() != null) value.put("fractionDigits", Integer.parseInt(row.fractionDigits()));
        }
        draft.put("value", value);

        if (row.country()    != null) draft.put("country",    row.country());
        if (row.channelKey() != null)
            draft.put("channel", Map.of("typeId", "channel", "key", row.channelKey()));
        if (row.validFrom()  != null) draft.put("validFrom",  row.validFrom());
        if (row.validUntil() != null) draft.put("validUntil", row.validUntil());

        // custom fields
        if (actualTypeKey != null) {
            Map<String, Object> fields = new LinkedHashMap<>();
            if (row.customDiscounted()    != null)
                fields.put("discounted", Boolean.parseBoolean(row.customDiscounted()));
            Map<String, String> bannerTitle = new LinkedHashMap<>();
            if (row.customBannerTitleEn() != null) bannerTitle.put("en", row.customBannerTitleEn());
            if (row.customBannerTitleDe() != null) bannerTitle.put("de", row.customBannerTitleDe());
            if (!bannerTitle.isEmpty()) fields.put("banner-title", bannerTitle);

            if (!fields.isEmpty()) {
                draft.put("custom", Map.of(
                        "type",   Map.of("typeId", "type", "key", actualTypeKey),
                        "fields", fields
                ));
            }
        }
        return draft;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, String> collectExistingPriceKeyToId(Map productMap) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Map masterData = (Map) productMap.get("masterData");
            if (masterData == null) return result;

            for (String version : new String[]{"staged", "current"}) {
                Map catalogData = (Map) masterData.get(version);
                if (catalogData == null) continue;

                List<Map> allVariants = new ArrayList<>();
                Map masterVariant = (Map) catalogData.get("masterVariant");
                if (masterVariant != null) allVariants.add(masterVariant);
                List<Map> variants = (List<Map>) catalogData.get("variants");
                if (variants != null) allVariants.addAll(variants);

                for (Map variant : allVariants) {
                    List<Map> prices = (List<Map>) variant.get("prices");
                    if (prices == null) continue;
                    for (Map price : prices) {
                        String k = (String) price.get("key");
                        String id = (String) price.get("id");
                        if (k != null && id != null) result.put(k, id);
                    }
                }
            }
        } catch (ClassCastException ex) {
            log.warn("{} Could not navigate product response for price lookup: {}", LOG, ex.getMessage());
        }
        return result;
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> fetchTypeResourceTypeIds(String key) {
        try {
            Map response = webClient.get()
                    .uri("/{project}/types/key={key}", settings.getProjectKey(), key)
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
            if (response == null) return null;
            Object ids = response.get("resourceTypeIds");
            return ids instanceof List ? (List<String>) ids : null;
        } catch (WebClientResponseException.NotFound ignored) {
            return null;
        }
    }

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

    @SuppressWarnings("rawtypes")
    private void postResource(String collection, Map<String, Object> draft) {
        webClient.post()
                .uri("/{project}/{collection}", settings.getProjectKey(), collection)
                .header("Authorization", "Bearer " + tokenService.getBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(draft)
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

    // ── Utilities ─────────────────────────────────────────────────────────────

    private Map<String, Object> fieldDef(String name, String label, String typeName) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type",     Map.of("name", typeName));
        def.put("name",     name);
        def.put("label",    Map.of("en", label));
        def.put("required", false);
        return def;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('-', ' ');
    }

}

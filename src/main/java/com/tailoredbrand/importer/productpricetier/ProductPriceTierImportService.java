package com.tailoredbrand.importer.productpricetier;

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
import java.util.stream.Collectors;

/**
 * Applies tier pricing to existing product prices via the CT Products update API.
 *
 * <h3>Per-price logic</h3>
 * <ol>
 *   <li>Parse the CSV into {@link ProductPriceTierGroup}s (one group per price key with
 *       all its tier rows).</li>
 *   <li>Batch groups by product key to minimise GET calls.</li>
 *   <li>{@code GET /{project}/products/key={productKey}} — fetch the full staged product
 *       (as raw {@code Map}) to extract the existing price data by {@code priceKey}.</li>
 *   <li>If the price is not found, the group is recorded as an error.</li>
 *   <li>Build a {@code changePrice} action: copy the existing price, remove read-only
 *       fields, then add the {@code tiers} array built from the CSV rows.</li>
 *   <li>{@code POST /{project}/products/{id}} — all {@code changePrice} actions for a
 *       product in a single call to avoid version conflicts.</li>
 * </ol>
 *
 * <p>Updates apply to the <em>staged</em> version; publish separately if needed.</p>
 */
@Service
@Slf4j
public class ProductPriceTierImportService {

    private static final String LOG = "[TIER IMPORT]";

    /** Price fields that CT treats as read-only and must not appear in a PriceDraft. */
    private static final Set<String> PRICE_READ_ONLY_FIELDS =
            Set.of("id", "discountedPrice", "discounted");

    private final CommerceToolsSettings     settings;
    private final CommerceToolsTokenService tokenService;
    private final ProductPriceTierCsvParser parser;
    private final WebClient                 webClient;

    public ProductPriceTierImportService(CommerceToolsSettings settings,
                                         CommerceToolsTokenService tokenService,
                                         ProductPriceTierCsvParser parser) {
        this.settings     = settings;
        this.tokenService = tokenService;
        this.parser       = parser;
        this.webClient    = buildWebClient();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public List<ImportResult> importPriceTiers(InputStream csvStream) throws IOException {
        List<ProductPriceTierGroup> groups = parser.parse(csvStream);
        log.info("{} Processing {} price-tier group(s)", LOG, groups.size());

        // Batch by product key to issue one GET + one POST per product
        Map<String, List<ProductPriceTierGroup>> byProduct = groups.stream()
                .filter(g -> g.productKey() != null)
                .collect(Collectors.groupingBy(ProductPriceTierGroup::productKey,
                        LinkedHashMap::new, Collectors.toList()));

        List<ImportResult> results = new ArrayList<>();
        for (Map.Entry<String, List<ProductPriceTierGroup>> entry : byProduct.entrySet()) {
            results.addAll(processProductGroups(entry.getKey(), entry.getValue()));
        }
        return results;
    }

    // ── Per-product batch processing ──────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<ImportResult> processProductGroups(String productKey,
                                                     List<ProductPriceTierGroup> groups) {
        log.info("{} ► Processing | productKey={} | {} price(s)", LOG, productKey, groups.size());

        try {
            // GET product as raw Map to preserve full price structure for changePrice
            Map productMap = fetchProductRaw(productKey);
            if (productMap == null) {
                log.warn("{} ✗ product not found | productKey={}", LOG, productKey);
                return groups.stream()
                        .map(g -> ImportResult.failure(g.priceKey(), 404,
                                "Product not found: " + productKey))
                        .toList();
            }

            String productId = (String) productMap.get("id");
            long version     = ((Number) productMap.get("version")).longValue();

            List<Map<String, Object>> allPrices = getAllPricesRaw(productMap);
            List<Map<String, Object>> actions   = new ArrayList<>();
            List<ImportResult> results          = new ArrayList<>();

            for (ProductPriceTierGroup group : groups) {
                Optional<Map<String, Object>> existing = findPriceByKey(allPrices, group.priceKey());
                if (existing.isEmpty()) {
                    // Price doesn't exist yet — create it via addPrice using the first tier's
                    // currency/amount as the base price value, then include all tiers.
                    log.info("{} ⚠ price '{}' not on product — using addPrice+tiers fallback",
                            LOG, group.priceKey());
                    actions.add(buildAddPriceWithTiers(group));
                } else {
                    actions.add(buildChangePriceWithTiers(existing.get(), group.tiers()));
                    log.info("{} ► changePrice+tiers | priceKey={} tiers={}",
                            LOG, group.priceKey(), group.tiers().size());
                }
                results.add(ImportResult.updated(group.priceKey()));
            }

            if (!actions.isEmpty()) {
                updateProduct(productId, version, actions);
                log.info("{} ✓ updated | productKey={} | {} action(s)", LOG, productKey, actions.size());
            }
            return results;

        } catch (WebClientResponseException ex) {
            log.error("{} ✗ CT error | productKey={} | status={} | body={}",
                    LOG, productKey, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            return groups.stream()
                    .map(g -> ImportResult.failure(g.priceKey(), ex.getStatusCode().value(),
                            ex.getResponseBodyAsString()))
                    .toList();
        } catch (Exception ex) {
            log.error("{} ✗ unexpected | productKey={}", LOG, productKey, ex);
            return groups.stream()
                    .map(g -> ImportResult.failure(g.priceKey(), 0, ex.getMessage()))
                    .toList();
        }
    }

    // ── Action builder ────────────────────────────────────────────────────────

    /**
     * Builds a {@code changePrice} action by:
     * <ol>
     *   <li>Deep-copying the existing price map.</li>
     *   <li>Removing CT read-only fields ({@code id}, {@code discountedPrice}, etc.).</li>
     *   <li>Adding the {@code tiers} array from the CSV rows.</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildChangePriceWithTiers(Map<String, Object> existingPrice,
                                                           List<ProductPriceTierRecord> tierRows) {
        String priceId = (String) existingPrice.get("id");

        // Deep copy and strip read-only fields
        Map<String, Object> priceDraft = new LinkedHashMap<>(existingPrice);
        PRICE_READ_ONLY_FIELDS.forEach(priceDraft::remove);

        // Remove nested discounted / discountedPrice that may appear from CT response
        priceDraft.remove("discountedPrice");

        // Build tiers
        List<Map<String, Object>> tiers = tierRows.stream()
                .filter(r -> r.minimumQuantity() != null)
                .map(r -> {
                    Map<String, Object> tier = new LinkedHashMap<>();
                    tier.put("minimumQuantity", Integer.parseInt(r.minimumQuantity()));
                    tier.put("value", buildTierValue(r));
                    return tier;
                })
                .toList();
        priceDraft.put("tiers", tiers);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("action",  "changePrice");
        action.put("priceId", priceId);
        action.put("price",   priceDraft);
        return action;
    }

    /**
     * Fallback for when the price key doesn't yet exist on the product.
     * Uses the first tier row's currency, type, and amount as the base price value
     * and includes all rows as volume tiers.
     */
    private Map<String, Object> buildAddPriceWithTiers(ProductPriceTierGroup group) {
        ProductPriceTierRecord first = group.tiers().get(0);

        List<Map<String, Object>> tiers = group.tiers().stream()
                .filter(r -> r.minimumQuantity() != null)
                .map(r -> {
                    Map<String, Object> tier = new LinkedHashMap<>();
                    tier.put("minimumQuantity", Integer.parseInt(r.minimumQuantity()));
                    tier.put("value", buildTierValue(r));
                    return tier;
                })
                .toList();

        Map<String, Object> priceDraft = new LinkedHashMap<>();
        priceDraft.put("key",   group.priceKey());
        priceDraft.put("value", buildTierValue(first));  // base price = first tier value
        priceDraft.put("tiers", tiers);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("action", "addPrice");
        if (group.sku() != null) action.put("sku", group.sku());
        action.put("price", priceDraft);
        return action;
    }

    private Map<String, Object> buildTierValue(ProductPriceTierRecord r) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("currencyCode", r.currencyCode() != null ? r.currencyCode() : "USD");
        String priceType = r.type() != null ? r.type() : "centPrecision";
        value.put("type", priceType);
        if (r.centAmount()     != null) value.put("centAmount",     Long.parseLong(r.centAmount()));
        if (r.fractionDigits() != null) value.put("fractionDigits", Integer.parseInt(r.fractionDigits()));
        return value;
    }

    // ── CT response navigation (raw Map) ─────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Map<String, Object>> getAllPricesRaw(Map productMap) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Map masterData = (Map) productMap.get("masterData");
            if (masterData == null) return result;
            Map staged = (Map) masterData.get("staged");
            if (staged == null) return result;

            collectPricesFromVariant((Map) staged.get("masterVariant"), result);
            List<Map> variants = (List<Map>) staged.get("variants");
            if (variants != null) variants.forEach(v -> collectPricesFromVariant(v, result));
        } catch (ClassCastException ex) {
            log.warn("{} Could not navigate product response: {}", LOG, ex.getMessage());
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void collectPricesFromVariant(Map variant, List<Map<String, Object>> target) {
        if (variant == null) return;
        List<Map> prices = (List<Map>) variant.get("prices");
        if (prices != null) {
            for (Map p : prices) {
                target.add((Map<String, Object>) p);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> findPriceByKey(List<Map<String, Object>> prices,
                                                          String priceKey) {
        return prices.stream()
                .filter(p -> priceKey.equals(p.get("key")))
                .findFirst();
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

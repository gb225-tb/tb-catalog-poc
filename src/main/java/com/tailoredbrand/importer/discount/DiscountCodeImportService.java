package com.tailoredbrand.importer.discount;

import com.tailoredbrand.commerce.CommerceToolsSettings;
import com.tailoredbrand.commerce.CommerceToolsTokenService;
import com.tailoredbrand.importer.ImportResult;
import com.tailoredbrand.importer.discount.DiscountCodeModels.*;
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
import java.util.*;

/**
 * Imports Discount Codes from an uploaded CSV into CommerceTools.
 *
 * <h3>Pre-flight checks</h3>
 * <ul>
 *   <li><b>Cart discounts</b> — all cart-discount keys referenced in the {@code cartDiscounts}
 *       column are created as inactive placeholder discounts (0 % relative) if missing.
 *       Requires {@code manage_cart_discounts} scope.</li>
 *   <li><b>Custom types</b> — created if missing with the standard field set from the template.</li>
 * </ul>
 *
 * <h3>Per-code logic</h3>
 * <ol>
 *   <li>{@code GET /{project}/discount-codes/key={key}} — skip if already exists.</li>
 *   <li>{@code POST /{project}/discount-codes} — create and record result.</li>
 * </ol>
 */
@Service
@Slf4j
public class DiscountCodeImportService {

    private static final String LOG = "[DISCOUNT IMPORT]";

    private final CommerceToolsSettings     settings;
    private final CommerceToolsTokenService tokenService;
    private final WebClient                 webClient;

    public DiscountCodeImportService(CommerceToolsSettings settings,
                                     CommerceToolsTokenService tokenService) {
        this.settings     = settings;
        this.tokenService = tokenService;
        this.webClient    = buildWebClient();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public List<ImportResult> importDiscountCodes(InputStream csvStream) throws IOException {
        List<DiscountCodeRecord> records = parseCsv(csvStream);
        log.info("{} Processing {} discount code(s)", LOG, records.size());

        runPreflightChecks(records);

        List<ImportResult> results = new ArrayList<>();
        for (DiscountCodeRecord rec : records) {
            results.add(processRecord(rec));
        }
        return results;
    }

    // ── CSV parsing ───────────────────────────────────────────────────────────

    private List<DiscountCodeRecord> parseCsv(InputStream csvStream) throws IOException {
        List<DiscountCodeRecord> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                if (line.isBlank()) continue;
                rows.add(DiscountCodeRecord.fromCsvColumns(line.split(",", -1)));
            }
        }
        return rows;
    }

    // ── Pre-flight ────────────────────────────────────────────────────────────

    private void runPreflightChecks(List<DiscountCodeRecord> records) {
        log.info("{} ── Pre-flight checks ──────────────────────────", LOG);
        ensureCartDiscountsExist(records);
        ensureCustomTypesExist(records);
        log.info("{} ── Pre-flight complete ─────────────────────────", LOG);
    }

    /**
     * Creates placeholder cart discounts (inactive, 0 % relative) for any
     * referenced cart-discount key that doesn't yet exist in CT.
     * A unique {@code sortOrder} is derived from the key's hash to avoid conflicts.
     */
    private void ensureCartDiscountsExist(List<DiscountCodeRecord> records) {
        Set<String> cartDiscountKeys = new LinkedHashSet<>();
        records.forEach(r -> {
            if (r.cartDiscounts() != null) {
                Arrays.stream(r.cartDiscounts().split(";"))
                        .map(String::trim)
                        .filter(k -> !k.isBlank())
                        .forEach(cartDiscountKeys::add);
            }
        });

        cartDiscountKeys.forEach(key -> {
            try {
                if (ctResourceExists("cart-discounts", key)) {
                    log.info("{} ✓ cart-discount '{}' already exists", LOG, key);
                    return;
                }
                // sortOrder must be unique across all cart discounts in the project and
                // strictly between 0 (exclusive) and 1 (exclusive).
                // Cast to long before Math.abs to avoid Integer.MIN_VALUE overflow,
                // then add key.length() to reduce hash collisions between similar keys,
                // then clamp to 1..999_999_998 so the result is never 0.
                long sortVal = (Math.abs((long) key.hashCode() + key.length())
                        % 999_999_998L) + 1L;
                String sortOrder = "0." + String.format("%09d", sortVal);

                Map<String, Object> draft = new LinkedHashMap<>();
                draft.put("key",                  key);
                draft.put("name",                 Map.of("en", capitalize(key)));
                draft.put("value",                Map.of("type", "relative", "permyriad", 0));
                draft.put("cartPredicate",        "1 = 1");
                draft.put("sortOrder",            sortOrder);
                draft.put("isActive",             false);
                draft.put("requiresDiscountCode", true);
                // target is required for relative/absolute discounts; omitting it causes CT
                // to reject the draft with "giftLineItem" confusion. totalPrice is the
                // simplest valid target for a 0 % placeholder discount.
                draft.put("target",               Map.of("type", "totalPrice"));
                postResource("cart-discounts", draft);
                log.info("{} ✓ cart-discount '{}' created (placeholder) | sortOrder={}", LOG, key, sortOrder);
            } catch (WebClientResponseException ex) {
                // Log the full CT response body so the root cause is visible
                log.error("{} Pre-flight failed for cart-discount '{}' | status={} | body={}",
                        LOG, key, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            } catch (Exception ex) {
                log.error("{} Pre-flight failed for cart-discount '{}': {}", LOG, key, ex.getMessage());
            }
        });
    }

    private void ensureCustomTypesExist(List<DiscountCodeRecord> records) {
        Set<String> typeKeys = new LinkedHashSet<>();
        records.forEach(r -> {
            if (r.customTypeKey() != null) typeKeys.add(r.customTypeKey());
        });

        typeKeys.forEach(key -> {
            try {
                if (ctResourceExists("types", key)) {
                    log.info("{} ✓ custom type '{}' already exists", LOG, key);
                    return;
                }
                Map<String, Object> draft = new LinkedHashMap<>();
                draft.put("key",             key);
                draft.put("name",            Map.of("en", capitalize(key)));
                draft.put("description",     Map.of("en", "Discount code custom fields"));
                draft.put("resourceTypeIds", List.of("discount-code"));
                draft.put("fieldDefinitions", discountFieldDefs());
                postResource("types", draft);
                log.info("{} ✓ custom type '{}' created", LOG, key);
            } catch (WebClientResponseException ex) {
                log.error("{} Pre-flight failed for custom type '{}' | status={} | body={}",
                        LOG, key, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            } catch (Exception ex) {
                log.error("{} Pre-flight failed for custom type '{}': {}", LOG, key, ex.getMessage());
            }
        });
    }

    private List<Map<String, Object>> discountFieldDefs() {
        return List.of(
                fieldDef("date-time-field", "DateTime Field", "DateTime"),
                fieldDef("boolean-field",   "Boolean Field",  "Boolean")
        );
    }

    // ── Per-record processing ─────────────────────────────────────────────────

    private ImportResult processRecord(DiscountCodeRecord rec) {
        String key = rec.key();
        log.info("{} ► Processing | key={}", LOG, key);

        try {
            if (discountCodeExists(key)) {
                log.info("{} ✓ skip | already exists | key={}", LOG, key);
                return ImportResult.skipped(key);
            }
            DiscountCodeDraft draft = toDraft(rec);
            createDiscountCode(draft);
            log.info("{} ✓ created | key={}", LOG, key);
            return ImportResult.created(key);

        } catch (WebClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            log.error("{} ✗ CT error | key={} | status={} | body={}", LOG, key, ex.getStatusCode().value(), body);
            return ImportResult.failure(key, ex.getStatusCode().value(), body);
        } catch (Exception ex) {
            log.error("{} ✗ unexpected | key={}", LOG, key, ex);
            return ImportResult.failure(key, 0, ex.getMessage());
        }
    }

    // ── Draft builder ─────────────────────────────────────────────────────────

    private DiscountCodeDraft toDraft(DiscountCodeRecord r) {
        // name  (en-GB locale from column header)
        Map<String, String> name = r.nameEnGb() != null ? Map.of("en-GB", r.nameEnGb()) : null;

        // cartDiscounts — semicolon-separated keys
        List<ResourceIdentifier> cartDiscounts = r.cartDiscounts() != null
                ? Arrays.stream(r.cartDiscounts().split(";"))
                        .map(String::trim).filter(k -> !k.isBlank())
                        .map(k -> new ResourceIdentifier("cart-discount", k))
                        .toList()
                : null;

        // groups — semicolon-separated
        List<String> groups = r.groups() != null
                ? Arrays.stream(r.groups().split(";"))
                        .map(String::trim).filter(g -> !g.isBlank())
                        .toList()
                : null;

        // validFrom / validUntil: CT expects ISO-8601; append T00:00:00.000Z if date-only
        String validFrom  = toIsoDateTime(r.validFrom());
        String validUntil = toIsoDateTime(r.validUntil());

        // custom fields
        CustomFields custom = null;
        if (r.customTypeKey() != null) {
            Map<String, Object> fields = new LinkedHashMap<>();
            if (r.customDateTimeField() != null) fields.put("date-time-field", r.customDateTimeField());
            if (r.customBooleanField()   != null) fields.put("boolean-field",   Boolean.parseBoolean(r.customBooleanField()));
            custom = fields.isEmpty() ? null
                    : new CustomFields(new ResourceIdentifier("type", r.customTypeKey()), fields);
        }

        return new DiscountCodeDraft(
                r.key(), name, r.code(), cartDiscounts,
                r.isActive() != null ? Boolean.parseBoolean(r.isActive()) : null,
                validFrom, validUntil,
                r.maxApplications()            != null ? Integer.parseInt(r.maxApplications()) : null,
                r.maxApplicationsPerCustomer() != null ? Integer.parseInt(r.maxApplicationsPerCustomer()) : null,
                groups, custom
        );
    }

    private String toIsoDateTime(String value) {
        if (value == null) return null;
        return value.contains("T") ? value : value + "T00:00:00.000Z";
    }

    // ── CT API calls ──────────────────────────────────────────────────────────

    private boolean discountCodeExists(String key) {
        try {
            webClient.get()
                    .uri("/{project}/discount-codes/key={key}", settings.getProjectKey(), key)
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .retrieve()
                    .bodyToMono(DiscountCodeExistsResponse.class)
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
            return true;
        } catch (WebClientResponseException.NotFound ignored) {
            return false;
        }
    }

    private void createDiscountCode(DiscountCodeDraft draft) {
        webClient.post()
                .uri("/{project}/discount-codes", settings.getProjectKey())
                .header("Authorization", "Bearer " + tokenService.getBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(draft)
                .retrieve()
                .bodyToMono(DiscountCodeExistsResponse.class)
                .block(Duration.ofMillis(settings.getReadTimeoutMs()));
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

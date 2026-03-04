package com.tailoredbrand.importer.inventory;

import com.tailoredbrand.commerce.CommerceToolsSettings;
import com.tailoredbrand.commerce.CommerceToolsTokenService;
import com.tailoredbrand.importer.ImportResult;
import com.tailoredbrand.importer.inventory.InventoryModels.*;
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
 * Imports Inventory Entries from an uploaded CSV into CommerceTools.
 *
 * <h3>Pre-flight checks</h3>
 * <ul>
 *   <li><b>Supply channels</b> — any channel key in {@code supplyChannel.key} is created
 *       with the {@code InventorySupply} role if missing.</li>
 *   <li><b>Custom types</b> — checks whether the referenced custom type already exists
 *       <em>and</em> supports the {@code inventory-entry} resource type. If the type
 *       exists but was created for a different resource (e.g., {@code discount-code}),
 *       a derived key {@code {key}-inventory} is auto-created and the mapping is applied
 *       transparently before building each draft.</li>
 * </ul>
 *
 * <h3>Per-entry logic</h3>
 * <ol>
 *   <li>{@code GET /{project}/inventory/key={key}} — skip if already exists.</li>
 *   <li>{@code POST /{project}/inventory} — create and record result.</li>
 * </ol>
 */
@Service
@Slf4j
public class InventoryImportService {

    private static final String LOG = "[INVENTORY IMPORT]";

    private final CommerceToolsSettings     settings;
    private final CommerceToolsTokenService tokenService;
    private final WebClient                 webClient;

    public InventoryImportService(CommerceToolsSettings settings,
                                  CommerceToolsTokenService tokenService) {
        this.settings     = settings;
        this.tokenService = tokenService;
        this.webClient    = buildWebClient();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public List<ImportResult> importInventory(InputStream csvStream) throws IOException {
        List<InventoryRecord> records = parseCsv(csvStream);
        log.info("{} Processing {} inventory entry(ies)", LOG, records.size());

        Map<String, String> customTypeKeyMap = runPreflightChecks(records);

        List<ImportResult> results = new ArrayList<>();
        for (InventoryRecord rec : records) {
            results.add(processRecord(rec, customTypeKeyMap));
        }
        return results;
    }

    // ── CSV parsing ───────────────────────────────────────────────────────────

    private List<InventoryRecord> parseCsv(InputStream csvStream) throws IOException {
        List<InventoryRecord> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                if (line.isBlank()) continue;
                rows.add(InventoryRecord.fromCsvColumns(line.split(",", -1)));
            }
        }
        return rows;
    }

    // ── Pre-flight ────────────────────────────────────────────────────────────

    /**
     * Returns a mapping of CSV custom-type keys → actual CT type keys to use in drafts.
     * If a key is compatible as-is, it maps to itself. If it was already taken by a
     * different resource type, it maps to a derived {@code {key}-inventory} key.
     */
    private Map<String, String> runPreflightChecks(List<InventoryRecord> records) {
        log.info("{} ── Pre-flight checks ──────────────────────────", LOG);
        ensureSupplyChannelsExist(records);
        Map<String, String> customTypeKeyMap = ensureCustomTypesExist(records);
        log.info("{} ── Pre-flight complete ─────────────────────────", LOG);
        return customTypeKeyMap;
    }

    private void ensureSupplyChannelsExist(List<InventoryRecord> records) {
        Set<String> channelKeys = new LinkedHashSet<>();
        records.forEach(r -> {
            if (r.supplyChannelKey() != null) channelKeys.add(r.supplyChannelKey());
        });

        channelKeys.forEach(key -> {
            try {
                if (ctResourceExists("channels", key)) {
                    log.info("{} ✓ channel '{}' already exists", LOG, key);
                    return;
                }
                Map<String, Object> draft = new LinkedHashMap<>();
                draft.put("key",   key);
                draft.put("roles", List.of("InventorySupply"));
                postResource("channels", draft);
                log.info("{} ✓ supply channel '{}' created", LOG, key);
            } catch (WebClientResponseException ex) {
                log.error("{} Pre-flight failed for channel '{}' | status={} | body={}",
                        LOG, key, ex.getStatusCode().value(), ex.getResponseBodyAsString());
            } catch (Exception ex) {
                log.error("{} Pre-flight failed for channel '{}': {}", LOG, key, ex.getMessage());
            }
        });
    }

    /**
     * For each custom type key in the CSV:
     * <ol>
     *   <li>GET the existing CT type (if any) and inspect its {@code resourceTypeIds}.</li>
     *   <li>If it already supports {@code inventory-entry} → use as-is.</li>
     *   <li>If it exists for a <em>different</em> resource (e.g., {@code discount-code}) →
     *       derive key {@code {csvKey}-inventory} and create/reuse that one instead.</li>
     *   <li>If it doesn't exist at all → create it under the original key.</li>
     * </ol>
     */
    private Map<String, String> ensureCustomTypesExist(List<InventoryRecord> records) {
        Map<String, String> keyMap = new LinkedHashMap<>();

        Set<String> typeKeys = new LinkedHashSet<>();
        records.forEach(r -> {
            if (r.customTypeKey() != null) typeKeys.add(r.customTypeKey());
        });

        typeKeys.forEach(csvKey -> {
            try {
                List<String> existingResourceTypeIds = fetchTypeResourceTypeIds(csvKey);

                if (existingResourceTypeIds != null) {
                    if (existingResourceTypeIds.contains("inventory-entry")) {
                        log.info("{} ✓ custom type '{}' exists and supports inventory-entry", LOG, csvKey);
                        keyMap.put(csvKey, csvKey);
                    } else {
                        // Type exists but is locked to a different resource — derive a new key
                        String derivedKey = csvKey + "-inventory";
                        keyMap.put(csvKey, derivedKey);
                        log.warn("{} ⚠ custom type '{}' is restricted to {} (not inventory-entry) → using '{}'",
                                LOG, csvKey, existingResourceTypeIds, derivedKey);
                        ensureInventoryType(derivedKey);
                    }
                    return;
                }

                // Type does not exist — create it under the original key
                createInventoryType(csvKey);
                keyMap.put(csvKey, csvKey);
                log.info("{} ✓ custom type '{}' created for inventory-entry", LOG, csvKey);

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

    /** Returns the {@code resourceTypeIds} list of an existing CT type, or {@code null} if not found. */
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

    /** Creates or verifies the derived {@code {key}-inventory} type for inventory-entry. */
    private void ensureInventoryType(String derivedKey) {
        List<String> existing = fetchTypeResourceTypeIds(derivedKey);
        if (existing != null) {
            log.info("{} ✓ derived custom type '{}' already exists", LOG, derivedKey);
            return;
        }
        createInventoryType(derivedKey);
        log.info("{} ✓ derived custom type '{}' created for inventory-entry", LOG, derivedKey);
    }

    private void createInventoryType(String key) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("key",             key);
        draft.put("name",            Map.of("en", capitalize(key)));
        draft.put("description",     Map.of("en", "Inventory entry custom fields"));
        draft.put("resourceTypeIds", List.of("inventory-entry"));
        draft.put("fieldDefinitions", inventoryFieldDefs());
        postResource("types", draft);
    }

    private List<Map<String, Object>> inventoryFieldDefs() {
        return List.of(
                fieldDef("boolean-field",           "Boolean Field",          "Boolean"),
                fieldDef("date-field",              "Date Field",             "Date"),
                fieldDef("date-time-field",         "DateTime Field",         "DateTime"),
                fieldDef("enum-field",              "Enum Field",             "String"),
                fieldDef("localized-enum-field",    "Localized Enum Field",   "String"),
                fieldDef("localized-string-field",  "Localized String Field", "LocalizedString"),
                fieldDef("number-field",            "Number Field",           "Number"),
                fieldDef("string-field",            "String Field",           "String"),
                fieldDef("time-field",              "Time Field",             "Time"),
                fieldDef("money-field",             "Money Field",            "Money")
        );
    }

    // ── Per-record processing ─────────────────────────────────────────────────

    private ImportResult processRecord(InventoryRecord rec, Map<String, String> customTypeKeyMap) {
        String key = rec.key() != null ? rec.key() : rec.sku();
        log.info("{} ► Processing | key={} sku={}", LOG, key, rec.sku());

        try {
            if (rec.key() != null && inventoryExists(rec.key())) {
                log.info("{} ✓ skip | already exists | key={}", LOG, key);
                return ImportResult.skipped(key);
            }
            InventoryEntryDraft draft = toDraft(rec, customTypeKeyMap);
            createInventoryEntry(draft);
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

    private InventoryEntryDraft toDraft(InventoryRecord r, Map<String, String> customTypeKeyMap) {
        ResourceIdentifier supplyChannel = r.supplyChannelKey() != null
                ? new ResourceIdentifier("channel", r.supplyChannelKey()) : null;

        CustomFields custom = null;
        if (r.customTypeKey() != null) {
            // Use the mapped key — may differ from CSV key if the original type was incompatible
            String actualTypeKey = customTypeKeyMap.getOrDefault(r.customTypeKey(), r.customTypeKey());

            Map<String, Object> fields = new LinkedHashMap<>();
            if (r.customBooleanField()            != null) fields.put("boolean-field",         Boolean.parseBoolean(r.customBooleanField()));
            if (r.customDateField()               != null) fields.put("date-field",             r.customDateField());
            if (r.customDateTimeField()           != null) fields.put("date-time-field",        r.customDateTimeField());
            if (r.customEnumField()               != null) fields.put("enum-field",             r.customEnumField());
            if (r.customLocalizedEnumField()      != null) fields.put("localized-enum-field",   r.customLocalizedEnumField());
            if (r.customLocalizedStringFieldEn()  != null) fields.put("localized-string-field", Map.of("en", r.customLocalizedStringFieldEn()));
            if (r.customNumberField()             != null) fields.put("number-field",           Long.parseLong(r.customNumberField()));
            if (r.customStringField()             != null) fields.put("string-field",           r.customStringField());
            if (r.customTimeField()               != null) fields.put("time-field",             r.customTimeField());
            if (r.customMoneyFieldCurrencyCode()  != null && r.customMoneyFieldCentAmount() != null)
                fields.put("money-field", Map.of(
                        "currencyCode",   r.customMoneyFieldCurrencyCode(),
                        "centAmount",     Long.parseLong(r.customMoneyFieldCentAmount()),
                        "type",           r.customMoneyFieldType() != null ? r.customMoneyFieldType() : "centPrecision",
                        "fractionDigits", r.customMoneyFieldFractionDigits() != null
                                ? Integer.parseInt(r.customMoneyFieldFractionDigits()) : 2
                ));
            custom = fields.isEmpty() ? null
                    : new CustomFields(new ResourceIdentifier("type", actualTypeKey), fields);
        }

        return new InventoryEntryDraft(
                r.key(),
                r.sku(),
                r.quantityOnStock()   != null ? Long.parseLong(r.quantityOnStock())     : null,
                r.restockableInDays() != null ? Integer.parseInt(r.restockableInDays())  : null,
                supplyChannel,
                custom
        );
    }

    // ── CT API calls ──────────────────────────────────────────────────────────

    private boolean inventoryExists(String key) {
        try {
            webClient.get()
                    .uri("/{project}/inventory/key={key}", settings.getProjectKey(), key)
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .retrieve()
                    .bodyToMono(InventoryExistsResponse.class)
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
            return true;
        } catch (WebClientResponseException.NotFound ignored) {
            return false;
        }
    }

    private void createInventoryEntry(InventoryEntryDraft draft) {
        webClient.post()
                .uri("/{project}/inventory", settings.getProjectKey())
                .header("Authorization", "Bearer " + tokenService.getBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(draft)
                .retrieve()
                .bodyToMono(InventoryExistsResponse.class)
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

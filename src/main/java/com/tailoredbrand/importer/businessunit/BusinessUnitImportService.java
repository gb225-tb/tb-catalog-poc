package com.tailoredbrand.importer.businessunit;

import com.tailoredbrand.commerce.CommerceToolsSettings;
import com.tailoredbrand.commerce.CommerceToolsTokenService;
import com.tailoredbrand.importer.ImportResult;
import com.tailoredbrand.importer.businessunit.BusinessUnitModels.*;
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
 * Imports Business Units from an uploaded CSV into CommerceTools.
 *
 * <h3>Pre-flight checks (auto-create missing CT resources)</h3>
 * <ol>
 *   <li><b>Custom types</b> — {@code addr-custom-type} (address time-zone field) and
 *       {@code bu-custom-type} (employee-count field) are created if missing.</li>
 *   <li><b>Stores</b> — all store keys referenced in the CSV are created if missing.</li>
 *   <li><b>Associate roles</b> — all role keys referenced in the CSV are created if
 *       missing (requires {@code manage_associate_roles} scope on the API client).</li>
 * </ol>
 *
 * <h3>Per-BU logic</h3>
 * <ol>
 *   <li>Groups are sorted: Companies first, then Divisions (parent must exist first).</li>
 *   <li>{@code GET /{project}/business-units/key={key}} — skip if already exists.</li>
 *   <li>{@code POST /{project}/business-units} — create and record result.</li>
 * </ol>
 *
 * <li><b>Customers</b> — all customer keys referenced in {@code associates.customer.key}
 *     are created as placeholder accounts if missing (requires
 *     {@code manage_customers} scope). Name and email are derived from the key
 *     (e.g., {@code oliver-smith} → firstName: Oliver, lastName: Smith,
 *     email: oliver-smith@import.placeholder).</li>
 * </ol>
 */
@Service
@Slf4j
public class BusinessUnitImportService {

    private final CommerceToolsSettings     settings;
    private final CommerceToolsTokenService tokenService;
    private final BuImportCsvParser         parser;
    private final BuImportMapper            mapper;
    private final WebClient                 webClient;

    public BusinessUnitImportService(CommerceToolsSettings settings,
                                     CommerceToolsTokenService tokenService,
                                     BuImportCsvParser parser,
                                     BuImportMapper mapper) {
        this.settings     = settings;
        this.tokenService = tokenService;
        this.parser       = parser;
        this.mapper       = mapper;
        this.webClient    = buildWebClient();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public List<ImportResult> importBusinessUnits(InputStream csvStream) throws IOException {
        List<BuImportGroup> groups = parser.parse(csvStream);
        log.info("[BU IMPORT] Processing {} BU group(s)", groups.size());

        runPreflightChecks(groups);

        List<ImportResult> results = new ArrayList<>();
        for (BuImportGroup group : groups) {
            results.add(processGroup(group));
        }
        return results;
    }

    // ── Pre-flight ────────────────────────────────────────────────────────────

    private void runPreflightChecks(List<BuImportGroup> groups) {
        log.info("[BU IMPORT] ── Pre-flight checks ──────────────────────────");
        ensureCustomTypesExist(groups);
        ensureStoresExist(groups);
        ensureAssociateRolesExist(groups);
        ensureCustomersExist(groups);
        log.info("[BU IMPORT] ── Pre-flight complete ─────────────────────────");
    }

    // ── Pre-flight: CT custom types ───────────────────────────────────────────

    /**
     * Collects all {@code addresses.custom.type.key} and {@code custom.type.key}
     * values from the CSV and creates any missing CT custom types with their
     * expected field definitions.
     *
     * <p>Known type definitions (derived from the BU CSV template):</p>
     * <ul>
     *   <li>{@code addr-custom-type} — resource: {@code address},
     *       field: {@code time-zone} (String)</li>
     *   <li>{@code bu-custom-type} — resource: {@code business-unit},
     *       field: {@code employee-count} (Number)</li>
     * </ul>
     * Unknown type keys are created as empty types so the reference resolves.
     */
    private void ensureCustomTypesExist(List<BuImportGroup> groups) {
        // Collect distinct custom type keys referenced in the CSV
        Set<String> addrTypeKeys = new LinkedHashSet<>();
        Set<String> buTypeKeys   = new LinkedHashSet<>();

        for (BuImportGroup g : groups) {
            // address custom type keys (from header + any address continuation rows)
            collectNonBlank(addrTypeKeys, g.header().addressCustomTypeKey());
            g.addressRows().forEach(r -> collectNonBlank(addrTypeKeys, r.addressCustomTypeKey()));

            // BU-level custom type keys
            collectNonBlank(buTypeKeys, g.header().customTypeKey());
        }

        addrTypeKeys.forEach(key -> ensureCustomType(key,
                List.of("address"),
                List.of(fieldDef("time-zone",       "Time Zone",      "String")),
                "Address custom fields"));

        buTypeKeys.forEach(key -> ensureCustomType(key,
                List.of("business-unit"),
                List.of(fieldDef("employee-count",  "Employee Count", "Number")),
                "Business unit custom fields"));
    }

    private void ensureCustomType(String key, List<String> resourceTypeIds,
                                   List<Map<String, Object>> fieldDefs, String description) {
        try {
            if (ctResourceExists("types", key)) {
                log.info("[BU IMPORT] ✓ custom type '{}' already exists", key);
                return;
            }
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("key",             key);
            draft.put("name",            Map.of("en", capitalize(key)));
            draft.put("description",     Map.of("en", description));
            draft.put("resourceTypeIds", resourceTypeIds);
            draft.put("fieldDefinitions", fieldDefs);
            postResource("types", key, draft);
            log.info("[BU IMPORT] ✓ custom type '{}' created", key);
        } catch (Exception ex) {
            log.error("[BU IMPORT] Pre-flight failed for custom type '{}': {}", key, ex.getMessage());
        }
    }

    private Map<String, Object> fieldDef(String name, String label, String typeName) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type",     Map.of("name", typeName));
        def.put("name",     name);
        def.put("label",    Map.of("en", label));
        def.put("required", false);
        return def;
    }

    // ── Pre-flight: stores ────────────────────────────────────────────────────

    /**
     * Collects all store keys from the semicolon-separated {@code stores} column
     * and creates any that are missing.
     */
    private void ensureStoresExist(List<BuImportGroup> groups) {
        groups.stream()
                .map(g -> g.header().stores())
                .filter(v -> v != null && !v.isBlank())
                .flatMap(v -> Arrays.stream(v.split(";")))
                .map(String::trim)
                .filter(k -> !k.isBlank())
                .distinct()
                .forEach(key -> {
                    try {
                        if (ctResourceExists("stores", key)) {
                            log.info("[BU IMPORT] ✓ store '{}' already exists", key);
                            return;
                        }
                        Map<String, Object> draft = Map.of(
                                "key",  key,
                                "name", Map.of("en", capitalize(key))
                        );
                        postResource("stores", key, draft);
                        log.info("[BU IMPORT] ✓ store '{}' created", key);
                    } catch (Exception ex) {
                        log.error("[BU IMPORT] Pre-flight failed for store '{}': {}", key, ex.getMessage());
                    }
                });
    }

    // ── Pre-flight: associate roles ───────────────────────────────────────────

    /**
     * Collects all associate role keys from the CSV and creates any that are missing.
     * Requires {@code manage_associate_roles:data-import} scope on the API client.
     */
    private void ensureAssociateRolesExist(List<BuImportGroup> groups) {
        Set<String> roleKeys = new LinkedHashSet<>();
        for (BuImportGroup g : groups) {
            collectNonBlank(roleKeys, g.header().associateRoleKey());
            g.associateRows().forEach(r -> collectNonBlank(roleKeys, r.associateRoleKey()));
        }

        roleKeys.forEach(key -> {
            try {
                if (ctResourceExists("associate-roles", key)) {
                    log.info("[BU IMPORT] ✓ associate-role '{}' already exists", key);
                    return;
                }
                Map<String, Object> draft = Map.of(
                        "key",              key,
                        "name",             capitalize(key),
                        "buyerAssignable",  true
                );
                postResource("associate-roles", key, draft);
                log.info("[BU IMPORT] ✓ associate-role '{}' created", key);
            } catch (Exception ex) {
                // Non-fatal: associate roles may exist under a different scope or be pre-created
                log.warn("[BU IMPORT] Pre-flight skipped for associate-role '{}': {}", key, ex.getMessage());
            }
        });
    }

    // ── Pre-flight: customers ─────────────────────────────────────────────────

    /**
     * Collects all {@code associates.customer.key} values from the CSV and creates
     * a placeholder CT customer for each one that doesn't already exist.
     *
     * <p>The placeholder customer is created with:</p>
     * <ul>
     *   <li>{@code key} — as-is from the CSV (e.g., {@code oliver-smith})</li>
     *   <li>{@code firstName} / {@code lastName} — derived by splitting the key on
     *       {@code -} and capitalising each part (e.g., {@code Oliver}, {@code Smith})</li>
     *   <li>{@code email} — {@code {key}@import.placeholder}</li>
     *   <li>{@code password} — a fixed placeholder string (customer cannot log in)</li>
     * </ul>
     * Requires {@code manage_customers:data-import} scope on the API client.
     */
    private void ensureCustomersExist(List<BuImportGroup> groups) {
        Set<String> customerKeys = new LinkedHashSet<>();
        for (BuImportGroup g : groups) {
            collectNonBlank(customerKeys, g.header().associateCustomerKey());
            g.associateRows().forEach(r -> collectNonBlank(customerKeys, r.associateCustomerKey()));
        }

        customerKeys.forEach(key -> {
            try {
                if (ctResourceExists("customers", key)) {
                    log.info("[BU IMPORT] ✓ customer '{}' already exists", key);
                    return;
                }
                String[] parts = key.split("-");
                String firstName = capitalize(parts[0]);
                String lastName  = parts.length > 1
                        ? capitalize(String.join("-", Arrays.copyOfRange(parts, 1, parts.length)))
                        : firstName;

                Map<String, Object> draft = new LinkedHashMap<>();
                draft.put("key",       key);
                draft.put("firstName", firstName);
                draft.put("lastName",  lastName);
                draft.put("email",     key + "@import.placeholder");
                draft.put("password",  "Placeholder@123!");
                postResource("customers", key, draft);
                log.info("[BU IMPORT] ✓ customer '{}' created (placeholder)", key);
            } catch (Exception ex) {
                log.error("[BU IMPORT] Pre-flight failed for customer '{}': {}", key, ex.getMessage());
            }
        });
    }

    // ── Per-BU processing ─────────────────────────────────────────────────────

    private ImportResult processGroup(BuImportGroup group) {
        String key  = group.header().key();
        String type = group.isDivision() ? "Division" : "Company";
        log.info("[BU IMPORT] ► Processing {} | key={}", type, key);

        try {
            if (buExists(key)) {
                log.info("[BU IMPORT] ✓ skip | BU already exists | key={}", key);
                return ImportResult.skipped(key);
            }

            BusinessUnitDraft draft = mapper.toBusinessUnitDraft(group);
            createBusinessUnit(draft);
            log.info("[BU IMPORT] ✓ created {} | key={}", type, key);
            return ImportResult.created(key);

        } catch (WebClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            log.error("[BU IMPORT] ✗ CT error | key={} | status={} | body={}",
                    key, ex.getStatusCode().value(), body);
            return ImportResult.failure(key, ex.getStatusCode().value(), body);
        } catch (Exception ex) {
            log.error("[BU IMPORT] ✗ unexpected error | key={}", key, ex);
            return ImportResult.failure(key, 0, ex.getMessage());
        }
    }

    // ── CT API calls ──────────────────────────────────────────────────────────

    private boolean buExists(String key) {
        try {
            webClient.get()
                    .uri("/{project}/business-units/key={key}", settings.getProjectKey(), key)
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .retrieve()
                    .bodyToMono(BuExistsResponse.class)
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
            return true;
        } catch (WebClientResponseException.NotFound ignored) {
            return false;
        }
    }

    private void createBusinessUnit(BusinessUnitDraft draft) {
        webClient.post()
                .uri("/{project}/business-units", settings.getProjectKey())
                .header("Authorization", "Bearer " + tokenService.getBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(draft)
                .retrieve()
                .bodyToMono(BuExistsResponse.class)
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

    private static void collectNonBlank(Set<String> target, String value) {
        if (value != null && !value.isBlank()) target.add(value.trim());
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0))
                + s.substring(1).replace('-', ' ').replace('_', ' ');
    }
}

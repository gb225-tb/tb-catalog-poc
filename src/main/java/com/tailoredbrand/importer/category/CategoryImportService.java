package com.tailoredbrand.importer.category;

import com.tailoredbrand.commerce.CommerceToolsSettings;
import com.tailoredbrand.commerce.CommerceToolsTokenService;
import com.tailoredbrand.importer.ImportResult;
import com.tailoredbrand.importer.category.CategoryImportModels.*;
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
 * Imports Categories from an uploaded CSV into CommerceTools.
 *
 * <h3>Pre-flight checks</h3>
 * <ul>
 *   <li>Custom types referenced in {@code custom.type.key} are created if missing.</li>
 * </ul>
 *
 * <h3>Per-category logic</h3>
 * <ol>
 *   <li>Groups are sorted: top-level categories first, then children.</li>
 *   <li>{@code GET /{project}/categories/key={key}} — skip if already exists.</li>
 *   <li>{@code POST /{project}/categories} — create and record result.</li>
 * </ol>
 */
@Service
@Slf4j
public class CategoryImportService {

    private static final String LOG = "[CATEGORY IMPORT]";

    private final CommerceToolsSettings     settings;
    private final CommerceToolsTokenService tokenService;
    private final CategoryImportCsvParser   parser;
    private final CategoryImportMapper      mapper;
    private final WebClient                 webClient;

    public CategoryImportService(CommerceToolsSettings settings,
                                 CommerceToolsTokenService tokenService,
                                 CategoryImportCsvParser parser,
                                 CategoryImportMapper mapper) {
        this.settings     = settings;
        this.tokenService = tokenService;
        this.parser       = parser;
        this.mapper       = mapper;
        this.webClient    = buildWebClient();
    }

    // ── Public entry point ────────────────────────────────────────────────────

    public List<ImportResult> importCategories(InputStream csvStream) throws IOException {
        List<CategoryImportGroup> groups = parser.parse(csvStream);
        log.info("{} Processing {} category group(s)", LOG, groups.size());

        runPreflightChecks(groups);

        List<ImportResult> results = new ArrayList<>();
        for (CategoryImportGroup group : groups) {
            results.add(processGroup(group));
        }
        return results;
    }

    // ── Pre-flight ────────────────────────────────────────────────────────────

    private void runPreflightChecks(List<CategoryImportGroup> groups) {
        log.info("{} ── Pre-flight checks ──────────────────────────", LOG);
        ensureCustomTypesExist(groups);
        log.info("{} ── Pre-flight complete ─────────────────────────", LOG);
    }

    private void ensureCustomTypesExist(List<CategoryImportGroup> groups) {
        Set<String> typeKeys = new LinkedHashSet<>();
        groups.forEach(g -> {
            if (g.header().customTypeKey() != null) typeKeys.add(g.header().customTypeKey());
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
                draft.put("description",     Map.of("en", "Category custom fields"));
                draft.put("resourceTypeIds", List.of("category"));
                draft.put("fieldDefinitions", categoryFieldDefs());
                postResource("types", draft);
                log.info("{} ✓ custom type '{}' created", LOG, key);
            } catch (Exception ex) {
                log.error("{} Pre-flight failed for custom type '{}': {}", LOG, key, ex.getMessage());
            }
        });
    }

    private List<Map<String, Object>> categoryFieldDefs() {
        return List.of(
                fieldDef("boolean-field",           "Boolean Field",          "Boolean"),
                fieldDef("string-field",             "String Field",           "String"),
                fieldDef("localized-string-field",   "Localized String Field", "LocalizedString"),
                fieldDef("money-field",              "Money Field",            "Money"),
                fieldDef("enum-field",               "Enum Field",             "String"),
                fieldDef("date-field",               "Date Field",             "Date"),
                fieldDef("time-field",               "Time Field",             "Time"),
                fieldDef("date-time-field",          "DateTime Field",         "DateTime")
        );
    }

    // ── Per-category processing ───────────────────────────────────────────────

    private ImportResult processGroup(CategoryImportGroup group) {
        String key = group.header().key();
        log.info("{} ► Processing | key={}", LOG, key);

        try {
            if (categoryExists(key)) {
                log.info("{} ✓ skip | already exists | key={}", LOG, key);
                return ImportResult.skipped(key);
            }
            CategoryDraft draft = mapper.toCategoryDraft(group);
            createCategory(draft);
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

    // ── CT API calls ──────────────────────────────────────────────────────────

    private boolean categoryExists(String key) {
        try {
            webClient.get()
                    .uri("/{project}/categories/key={key}", settings.getProjectKey(), key)
                    .header("Authorization", "Bearer " + tokenService.getBearerToken())
                    .retrieve()
                    .bodyToMono(CategoryExistsResponse.class)
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
            return true;
        } catch (WebClientResponseException.NotFound ignored) {
            return false;
        }
    }

    private void createCategory(CategoryDraft draft) {
        webClient.post()
                .uri("/{project}/categories", settings.getProjectKey())
                .header("Authorization", "Bearer " + tokenService.getBearerToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(draft)
                .retrieve()
                .bodyToMono(CategoryExistsResponse.class)
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

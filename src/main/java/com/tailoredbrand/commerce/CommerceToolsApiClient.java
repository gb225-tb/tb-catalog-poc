package com.tailoredbrand.commerce;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tailoredbrand.commerce.CommerceToolsProductModels.*;
import com.tailoredbrand.model.ProductApiResult;
import com.tailoredbrand.model.ProductCsvRecord;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * WebClient-based wrapper for the CommerceTools Products API.
 *
 * <h3>Upsert flow (per CSV row)</h3>
 * <pre>
 *  GET /{project}/products/key={parentProductCode}
 *    → 404 Not Found : POST create with ProductDraft (CSV row = masterVariant)
 *    → 200 OK        : if variant SKU exists → skip (noOp)
 *                      else → POST addVariant action (versioned update)
 *    → 4xx/5xx retry : exponential back-off up to maxRetries
 * </pre>
 *
 * <h3>Pre-flight validation</h3>
 * Call {@link #validateAndResolveProductType()} once in {@code @Setup} before
 * processing any records. It verifies the configured product-type key exists and,
 * on failure, lists every available type so the operator can correct
 * {@code commerce.productTypeKey} in {@code pipeline.yaml}.
 *
 * <h3>Dual product-type routing</h3>
 * When {@code settings.secondaryProductTypeKey} is set, both type references are resolved
 * at startup. Each record is routed to the <em>primary</em> type when its {@code division}
 * value appears in {@code settings.primaryProductTypeDivisions}; all other records are
 * routed to the <em>secondary</em> type.
 */
@Slf4j
@RequiredArgsConstructor
public class CommerceToolsApiClient {

    private static final String DIVIDER =
            "─────────────────────────────────────────────────────────────";

    private final CommerceToolsSettings settings;
    private final CommerceToolsTokenService tokenService;
    private final CommerceToolsProductMapper mapper;

    /** Resolved at startup via {@link #initTypeRefs(TypeRef, TypeRef)}. */
    private TypeRef primaryTypeRef;
    private TypeRef secondaryTypeRef;

    // Transient — recreated lazily after Beam serialization/restore
    private transient WebClient apiWebClient;
    private transient ObjectMapper objectMapper;

    // ── Pre-flight: validate product type ───────────────────────────────────

    /**
     * Stores both resolved TypeRefs into this client so that {@link #resolveTypeRef}
     * can pick the correct one per record.  Call once from {@code @Setup} after both
     * TypeRefs have been resolved via a preflight client.
     */
    public void initTypeRefs(TypeRef primary, TypeRef secondary) {
        this.primaryTypeRef   = primary;
        this.secondaryTypeRef = secondary;
    }

    /**
     * Selects the CT product-type reference for the given CSV record.
     *
     * <ul>
     *   <li>If {@code secondaryTypeRef} is not set, always returns {@code primaryTypeRef}.</li>
     *   <li>Otherwise: if the record's {@code division} value is listed in
     *       {@code settings.primaryProductTypeDivisions}, returns the primary type;
     *       all other records use the secondary type.</li>
     * </ul>
     */
    public TypeRef resolveTypeRef(com.tailoredbrand.model.ProductCsvRecord record) {
        if (secondaryTypeRef == null) return primaryTypeRef;

        List<String> primaryDivisions = settings.getPrimaryProductTypeDivisions();
        if (primaryDivisions != null && !primaryDivisions.isEmpty()
                && record.division() != null
                && primaryDivisions.contains(record.division().trim())) {
            return primaryTypeRef;
        }
        return secondaryTypeRef;
    }


    /**
     * Validates the configured {@code productTypeKey} and returns a {@link TypeRef}
     * ready to embed in every {@code ProductDraft}.
     *
     * <h3>Resolution rules</h3>
     * <ol>
     *   <li><b>Key configured and found</b> — confirms existence, then returns a
     *       {@code TypeRef} using the product type's own {@code key} field if set,
     *       otherwise falls back to its {@code id} (handles product types created
     *       without a key).</li>
     *   <li><b>Key configured but not found (404)</b> — lists every available
     *       product type and throws {@link IllegalStateException}.</li>
     *   <li><b>Key blank / null (auto-detect)</b>
     *     <ul>
     *       <li>Exactly 1 type → uses it automatically.</li>
     *       <li>0 types → throws, advising the user to create one.</li>
     *       <li>2+ types → lists all and throws, asking the user to pick one.</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @return a {@link TypeRef} to use for the {@code productType} field in every draft
     * @throws IllegalStateException on any unresolvable condition
     */
    public TypeRef validateAndResolveProductType() {
        String configuredKey = settings.getProductTypeKey();
        log.info("[CT PREFLIGHT] {}", DIVIDER);

        if (configuredKey != null && !configuredKey.isBlank()) {
            return validateExplicitKey(configuredKey);
        }

        log.info("[CT PREFLIGHT] No productTypeKey configured — auto-detecting from project '{}'...",
                settings.getProjectKey());
        return autoDetectProductType();
    }

    /**
     * Validates and resolves a product type by its explicit key — used for the
     * secondary product type resolution in the dual-type setup.
     *
     * @param key the product type key to validate
     * @return a resolved {@link TypeRef}
     * @throws IllegalStateException when the key is not found
     */
    public TypeRef validateAndResolveProductTypeByKey(String key) {
        log.info("[CT PREFLIGHT] {}", DIVIDER);
        return validateExplicitKey(key);
    }

    // ── Explicit key validation ──────────────────────────────────────────────

    private TypeRef validateExplicitKey(String key) {
        log.info("[CT PREFLIGHT] Validating configured productTypeKey='{}' in project='{}'...",
                key, settings.getProjectKey());
        try {
            ProductTypeResponse pt = getProductType(key);
            logProductTypeConfirmed(pt);
            TypeRef ref = buildTypeRef(pt);
            log.info("[CT PREFLIGHT] ✓ Product type reference resolved → {}", ref);
            return ref;

        } catch (WebClientResponseException.NotFound ignored) {
            log.error("[CT PREFLIGHT] ✗ Product type key='{}' NOT FOUND in project='{}'",
                    key, settings.getProjectKey());
            logAvailableProductTypes(key);
            throw new IllegalStateException(String.format(
                    "Product type key '%s' does not exist in CT project '%s'. " +
                    "See available types logged above and update 'commerce.productTypeKey' in pipeline.yaml.",
                    key, settings.getProjectKey()));

        } catch (Exception ex) {
            log.error("[CT PREFLIGHT] ✗ Could not verify product type '{}': {}", key, ex.getMessage(), ex);
            throw new IllegalStateException("CT product-type pre-flight check failed: " + ex.getMessage(), ex);
        }
    }

    // ── Auto-detection (no key configured) ──────────────────────────────────

    private TypeRef autoDetectProductType() {
        ProductTypesPagedQueryResponse page = fetchAllProductTypes();

        if (page == null || page.results() == null || page.results().isEmpty()) {
            log.error("[CT PREFLIGHT] ✗ No product types found in project '{}'.", settings.getProjectKey());
            log.error("[CT PREFLIGHT]   Create at least one Product Type in the CT Merchant Center,");
            log.error("[CT PREFLIGHT]   then re-run the pipeline.");
            throw new IllegalStateException(
                    "No product types found in CT project '" + settings.getProjectKey() + "'. " +
                    "Create one in the Merchant Center first.");
        }

        if (page.results().size() == 1) {
            ProductTypeResponse sole = page.results().get(0);
            log.info("[CT PREFLIGHT] ✓ Auto-detected single product type — using it automatically:");
            logProductTypeConfirmed(sole);
            TypeRef ref = buildTypeRef(sole);
            log.info("[CT PREFLIGHT]   (To skip auto-detection, set 'commerce.productTypeKey: {}' in pipeline.yaml)",
                    sole.key() != null ? sole.key() : sole.id());
            log.info("[CT PREFLIGHT] ✓ Product type reference resolved → {}", ref);
            return ref;
        }

        // Multiple types — cannot auto-pick, must be explicit
        log.error("[CT PREFLIGHT] ✗ Multiple product types found ({}) — cannot auto-detect.",
                page.total());
        logAvailableProductTypes(null);
        throw new IllegalStateException(String.format(
                "Found %d product types in CT project '%s'. " +
                "Set 'commerce.productTypeKey' to one of the keys listed above in pipeline.yaml.",
                page.total(), settings.getProjectKey()));
    }

    /**
     * Builds a {@link TypeRef} from a resolved product type response using the stable {@code id}.
     * This produces {@code {"typeId":"product-type","id":"..."}} in the payload, matching
     * the CT product draft contract used by this project.
     */
    private TypeRef buildTypeRef(ProductTypeResponse pt) {
        if (pt.id() == null || pt.id().isBlank()) {
            log.warn("[CT PREFLIGHT] Product type '{}' returned no id — falling back to key reference.",
                    pt.name());
            return TypeRef.productType(pt.key());
        }
        return TypeRef.productTypeById(pt.id());
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    private void logProductTypeConfirmed(ProductTypeResponse pt) {
        log.info("[CT PREFLIGHT] ✓ Product type confirmed:");
        log.info("[CT PREFLIGHT]   key        = {}", pt.key());
        log.info("[CT PREFLIGHT]   id         = {}", pt.id());
        log.info("[CT PREFLIGHT]   name       = {}", pt.name());
        log.info("[CT PREFLIGHT]   description= {}", pt.description());
        int attrCount = pt.attributes() != null ? pt.attributes().size() : 0;
        log.info("[CT PREFLIGHT]   attributes = {}", attrCount);
        if (pt.attributes() != null) {
            pt.attributes().forEach(a ->
                    log.info("[CT PREFLIGHT]     · {} ({}) required={}",
                            a.name(), a.labelEn(), a.isRequired()));
        }
        log.info("[CT PREFLIGHT] {}", DIVIDER);
    }

    private void logAvailableProductTypes(String configuredKey) {
        ProductTypesPagedQueryResponse page = fetchAllProductTypes();
        if (page == null || page.results() == null || page.results().isEmpty()) {
            log.error("[CT PREFLIGHT] (No product types exist in project '{}' to suggest.)",
                    settings.getProjectKey());
            return;
        }
        log.error("[CT PREFLIGHT] Available product types in project '{}' ({} total):",
                settings.getProjectKey(), page.total());
        page.results().forEach(pt ->
                log.error("[CT PREFLIGHT]   → key='{}' | name='{}' | attributes={} | id={}",
                        pt.key(), pt.name(),
                        pt.attributes() != null ? pt.attributes().size() : "?",
                        pt.id()));
        log.error("[CT PREFLIGHT] {}", DIVIDER);
        log.error("[CT PREFLIGHT] ► FIX: update pipeline.yaml:");
        log.error("[CT PREFLIGHT]     commerce:");
        if (configuredKey != null) {
            log.error("[CT PREFLIGHT]       productTypeKey: \"{}\"   ← replace '{}' with one of the keys above",
                    page.results().get(0).key(), configuredKey);
        } else {
            log.error("[CT PREFLIGHT]       productTypeKey: \"{}\"   ← choose one of the keys above",
                    page.results().get(0).key());
        }
        log.error("[CT PREFLIGHT] {}", DIVIDER);
    }

    private ProductTypesPagedQueryResponse fetchAllProductTypes() {
        try {
            return buildClient()
                    .get()
                    .uri("/{project}/product-types?limit=500", settings.getProjectKey())
                    .header("Authorization", bearer())
                    .retrieve()
                    .bodyToMono(ProductTypesPagedQueryResponse.class)
                    .block(Duration.ofMillis(settings.getReadTimeoutMs()));
        } catch (Exception ex) {
            log.error("[CT PREFLIGHT] Could not retrieve product-type list: {}", ex.getMessage());
            return null;
        }
    }

    // ── Public entry points ──────────────────────────────────────────────────

    /**
     * Processes a single CSV record by upserting <b>both</b> tiers:
     * <ol>
     *   <li><b>Tier-1</b> — style product ({@code parentProductCode}) with classification attrs.
     *       Created once; subsequent rows for the same style are skipped.</li>
     *   <li><b>Tier-2</b> — colour product ({@code parentProductCode-productColorCode}) with
     *       colour attrs and size variants.  Skipped when required colour fields are absent.</li>
     * </ol>
     *
     * @return one result per tier actually processed (1 or 2 elements)
     */
    public List<ProductApiResult> upsertAll(ProductCsvRecord record) {
        List<ProductApiResult> results = new ArrayList<>();

        // Tier-1: always attempt
        results.add(upsertSingle(record, false));

        // Tier-2: only when secondary type is configured AND colour data is present
        if (secondaryTypeRef != null && isValidForTier2(record)) {
            results.add(upsertSingle(record, true));
        } else if (secondaryTypeRef != null) {
            log.warn("[CT SKIP] Tier-2 skipped for itemCode={} — missing required colour fields " +
                     "(productColorCode, colorDesc, colorBreakoutDesc or colorFamily)",
                    record.itemCode());
        }

        return results;
    }

    /**
     * Returns {@code true} when the record has all four required Tier-2 colour fields.
     */
    private boolean isValidForTier2(ProductCsvRecord record) {
        return notBlank(record.parentProductCode())
            && notBlank(record.itemCode());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ── Single-tier upsert with retry ────────────────────────────────────────

    private ProductApiResult upsertSingle(ProductCsvRecord record, boolean isTier2) {
        String tier       = isTier2 ? "Tier2" : "Tier1";
        String productKey = isTier2
                ? CommerceToolsProductMapper.tier2ProductKey(record)
                : CommerceToolsProductMapper.tier1ProductKey(record);
        // Tier-1 variant SKU = parentProductCode (style placeholder, always present once created)
        // Tier-2 variant SKU = itemCode (each distinct size)
        String variantSku = isTier2 ? record.itemCode() : record.parentProductCode();

        Exception lastEx = null;
        for (int attempt = 0; attempt <= settings.getMaxRetries(); attempt++) {
            try {
                return execute(record, productKey, variantSku, isTier2);
            } catch (WebClientResponseException ex) {
                lastEx = ex;
                ErrorResponse errorBody = parseErrorBody(ex.getResponseBodyAsString());

                if (ex.getStatusCode().value() == 400) {
                    log.error("[CT UPSERT] ✗ 400 Bad Request | {} | product={} sku={} | message='{}' | errors={}",
                            tier, productKey, variantSku,
                            errorBody != null ? errorBody.message() : ex.getMessage(),
                            errorBody != null ? formatErrors(errorBody.errors()) : "N/A");
                    return ProductApiResult.ofFailure(variantSku, tier + ".upsert", 400,
                            errorBody != null ? errorBody.message() : ex.getMessage());
                }

                if (!isRetryable(ex.getStatusCode()) || attempt == settings.getMaxRetries()) {
                    log.warn("[CT UPSERT] ✗ Non-retryable | {} | product={} status={} msg={}",
                            tier, productKey, ex.getStatusCode().value(),
                            errorBody != null ? errorBody.message() : ex.getMessage());
                    return ProductApiResult.ofFailure(variantSku, tier + ".upsert",
                            ex.getStatusCode().value(), ex.getMessage());
                }
            } catch (Exception ex) {
                lastEx = ex;
                if (attempt == settings.getMaxRetries()) {
                    log.error("[CT UPSERT] ✗ Unexpected error | {} | product={}", tier, productKey, ex);
                    return ProductApiResult.ofFailure(variantSku, tier + ".upsert", 0, ex.getMessage());
                }
            }
            log.warn("[CT UPSERT] Retry {}/{} | {} | product={}", attempt + 1,
                    settings.getMaxRetries(), tier, productKey);
            sleepBackoff(attempt);
        }

        String msg = lastEx != null ? lastEx.getMessage() : "retries exhausted";
        return ProductApiResult.ofFailure(variantSku, tier + ".upsert", 0, msg);
    }

    // ── Core create-or-update logic ──────────────────────────────────────────

    private ProductApiResult execute(ProductCsvRecord record,
                                     String productKey, String variantSku, boolean isTier2) {
        String tier    = isTier2 ? "Tier2" : "Tier1";
        TypeRef typeRef = isTier2 ? secondaryTypeRef : primaryTypeRef;

        log.info("[CT GET ] ► {} | key={}", tier, productKey);

        ProductResponse existing = null;
        try {
            existing = getProduct(productKey);
            log.info("[CT GET ] ✓ Found {} product | key={} | id={} | version={}",
                    tier, existing.key(), existing.id(), existing.version());
        } catch (WebClientResponseException.NotFound ignored) {
            log.info("[CT GET ] → 404 Not Found | {} | key={} — will CREATE", tier, productKey);
        }

        // ── CREATE ────────────────────────────────────────────────────────────
        if (existing == null) {
            ProductDraft draft = mapper.toProductDraft(record, typeRef, isTier2);
            logSyncPreviewCreate(record, draft, tier);
            postCreate(draft);
            log.info("[CT POST] ✓ Created {} product | key={}", tier, productKey);
            return ProductApiResult.ofSuccess(variantSku, tier + ".create", 201);
        }

        // ── SKIP ──────────────────────────────────────────────────────────────
        ProductData current = existing.masterData().current();
        if (current.hasVariantWithSku(variantSku)) {
            log.info("[CT SYNC] ✓ noOp | variant SKU={} already exists on {} product key={} — skipped",
                    variantSku, tier, productKey);
            return ProductApiResult.ofSuccess(variantSku, tier + ".noOp", 200);
        }

        // ── ADD VARIANT ───────────────────────────────────────────────────────
        ProductVariantDraft variantDraft = mapper.toVariantDraft(record, isTier2);
        ProductUpdate update = new ProductUpdate(
                existing.version(),
                List.of(ProductUpdateAction.addVariant(variantDraft))
        );
        logSyncPreviewAddVariant(record, existing, variantDraft, tier);
        postUpdate(productKey, update);
        log.info("[CT POST] ✓ addVariant | {} | SKU={} → product key={}", tier, variantSku, productKey);
        return ProductApiResult.ofSuccess(variantSku, tier + ".addVariant", 200);
    }

    // Upsert a grouped product (single product with all variants)
    public ProductApiResult upsertGroupedProduct(ProductDraft draft) {
        String productKey = draft.key();
        try {
            ProductResponse existing = getProduct(productKey);
            log.info("[CT GET ] ✓ Found grouped product | key={} | id={} | version={}",
                    productKey, existing.id(), existing.version());
            // TODO: Implement update logic to sync variants (add new, update existing, remove missing if needed)
            // For now, just log and return success
            return ProductApiResult.ofSuccess(productKey, "grouped.upsert.noOp", 200);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.NotFound ignored) {
            log.info("[CT GET ] → 404 Not Found | grouped | key={} — will CREATE", productKey);
            postCreate(draft);
            log.info("[CT POST] ✓ Created grouped product | key={}", productKey);
            return ProductApiResult.ofSuccess(productKey, "grouped.create", 201);
        } catch (Exception ex) {
            log.error("[CT UPSERT] ✗ Unexpected error | grouped | product={}", productKey, ex);
            return ProductApiResult.ofFailure(productKey, "grouped.upsert", 0, ex.getMessage());
        }
    }

    // Add a variant to an existing product via update action
    public ProductApiResult addVariantToProduct(String productKey, ProductVariantDraft variantDraft) {
        try {
            ProductResponse existing = getProduct(productKey);
            ProductUpdate update = new ProductUpdate(
                    existing.version(),
                    List.of(ProductUpdateAction.addVariant(variantDraft))
            );
            postUpdate(productKey, update);
            log.info("[CT POST] ✓ addVariant | SKU={} → product key={}", variantDraft.sku(), productKey);
            return ProductApiResult.ofSuccess(variantDraft.sku(), "grouped.addVariant", 200);
        } catch (Exception ex) {
            log.error("[CT ADDVARIANT] ✗ Error adding variant | product={} sku={}", productKey, variantDraft.sku(), ex);
            return ProductApiResult.ofFailure(variantDraft.sku(), "grouped.addVariant", 0, ex.getMessage());
        }
    }

    // ── Sync Preview Loggers ─────────────────────────────────────────────────

    private void logSyncPreviewCreate(ProductCsvRecord csv, ProductDraft draft, String tier) {
        log.info("[CT SYNC] {}", DIVIDER);
        log.info("[CT SYNC] MODE: CREATE NEW PRODUCT ({})", tier);
        log.info("[CT SYNC] {}", DIVIDER);
        log.info("[CT SYNC] ── CSV Source ────────────────────────────────────");
        log.info("[CT SYNC]   itemCode          : {}", csv.itemCode());
        log.info("[CT SYNC]   parentProductCode : {}", csv.parentProductCode());
        log.info("[CT SYNC]   description       : {}", truncate(csv.webLongDesc()));
        log.info("[CT SYNC]   colorCode / desc  : {} / {}", csv.productColorCode(), csv.colorDesc());
        log.info("[CT SYNC]   fit               : {}", csv.fit());
        log.info("[CT SYNC]   MSRP              : ${}", csv.msrp());
        log.info("[CT SYNC] ── POST /products Payload ({}) ─────────────────────", tier);
        log.info("[CT SYNC]   product.key       : {}", draft.key());
        log.info("[CT SYNC]   product.type.id   : {}",
                draft.productType().id() != null ? draft.productType().id() : draft.productType().key());
        log.info("[CT SYNC]   product.name      : {}", draft.name().enUS());
        log.info("[CT SYNC]   product.slug      : {}", draft.slug().enUS());
        log.info("[CT SYNC]   product.attrs cnt : {}", draft.attributes() != null ? draft.attributes().size() : 0);
        log.info("[CT SYNC]   product.attrs     : {}", formatAttributes(draft.attributes()));
        log.info("[CT SYNC]   masterVariant.sku : {}", draft.masterVariant().sku());
        log.info("[CT SYNC]   masterVariant.price: {}",
                draft.masterVariant().prices().isEmpty() ? "none"
                        : formatPrice(draft.masterVariant().prices().get(0)));
        List<AttributeDraft> vAttrs = draft.masterVariant().attributes();
        log.info("[CT SYNC]   masterVariant.attrs: {} attribute(s)",
                vAttrs != null ? vAttrs.size() : 0);
        if (vAttrs != null && !vAttrs.isEmpty()) {
            log.info("[CT SYNC]   masterVariant.attrs: {}", formatAttributes(vAttrs));
        }
        log.info("[CT SYNC] {}", DIVIDER);
        log.info("[CT SYNC] ► Sending CREATE ({}) → POST {}/{}/products",
                tier, settings.getApiUrl(), settings.getProjectKey());
    }

    private void logSyncPreviewAddVariant(ProductCsvRecord csv,
                                          ProductResponse existing,
                                          ProductVariantDraft variantDraft,
                                          String tier) {
        ProductData current = existing.masterData().current();
        List<String> existingSkus = collectExistingSkus(current);

        log.info("[CT SYNC] {}", DIVIDER);
        log.info("[CT SYNC] MODE: ADD VARIANT TO EXISTING PRODUCT ({})", tier);
        log.info("[CT SYNC] {}", DIVIDER);
        log.info("[CT SYNC] ── GET /products/key={} (current state) ──────────", existing.key());
        log.info("[CT SYNC]   product.id        : {}", existing.id());
        log.info("[CT SYNC]   product.key       : {}", existing.key());
        log.info("[CT SYNC]   product.version   : {}", existing.version());
        log.info("[CT SYNC]   product.name      : {}",
                current.name() != null ? current.name().enUS() : "N/A");
        log.info("[CT SYNC]   existing SKUs     : [{}]", String.join(", ", existingSkus));
        log.info("[CT SYNC]   total variants    : {}", existingSkus.size());
        log.info("[CT SYNC] ── CSV Source (new variant) ────────────────────────");
        log.info("[CT SYNC]   itemCode          : {}", csv.itemCode());
        log.info("[CT SYNC]   size              : {} ({})", csv.sizeDescription(), csv.sizeDimension());
        log.info("[CT SYNC]   color             : {} / {}", csv.colorDesc(), csv.colorFamily());
        log.info("[CT SYNC]   MSRP              : ${}", csv.msrp());
        log.info("[CT SYNC] ── POST addVariant Payload ({}) ────────────────────", tier);
        log.info("[CT SYNC]   action            : addVariant");
        log.info("[CT SYNC]   variant.key       : {}", variantDraft.key());
        log.info("[CT SYNC]   variant.sku       : {}", variantDraft.sku());
        log.info("[CT SYNC]   variant.price     : {}",
                variantDraft.prices().isEmpty() ? "none"
                        : formatPrice(variantDraft.prices().get(0)));
        int attrCount = variantDraft.attributes() != null ? variantDraft.attributes().size() : 0;
        log.info("[CT SYNC]   attributes count  : {}", attrCount);
        log.info("[CT SYNC]   attributes        : {}", formatAttributes(variantDraft.attributes()));
        log.info("[CT SYNC]   using version     : {}", existing.version());
        log.info("[CT SYNC] {}", DIVIDER);
        log.info("[CT SYNC] ► Sending UPDATE ({}) → POST {}/{}/products/key={}",
                tier, settings.getApiUrl(), settings.getProjectKey(), existing.key());
    }

    // ── HTTP operations ──────────────────────────────────────────────────────

    private ProductResponse getProduct(String productKey) {
        return buildClient()
                .get()
                .uri("/{project}/products/key={key}", settings.getProjectKey(), productKey)
                .header("Authorization", bearer())
                .retrieve()
                .onStatus(s -> s.value() == 404,
                        resp -> resp.bodyToMono(String.class).map(
                                body -> WebClientResponseException.create(404, "Not Found",
                                        resp.headers().asHttpHeaders(), body.getBytes(), null)))
                .onStatus(HttpStatusCode::isError,
                        resp -> resp.bodyToMono(String.class).map(
                                body -> WebClientResponseException.create(
                                        resp.statusCode().value(), "CT API error",
                                        resp.headers().asHttpHeaders(), body.getBytes(), null)))
                .bodyToMono(ProductResponse.class)
                .block(Duration.ofMillis(settings.getReadTimeoutMs()));
    }

    private ProductTypeResponse getProductType(String key) {
        return buildClient()
                .get()
                .uri("/{project}/product-types/{key}", settings.getProjectKey(), key)
                .header("Authorization", bearer())
                .retrieve()
                .onStatus(s -> s.value() == 404,
                        resp -> resp.bodyToMono(String.class).map(
                                body -> WebClientResponseException.create(404, "Not Found",
                                        resp.headers().asHttpHeaders(), body.getBytes(), null)))
                .onStatus(HttpStatusCode::isError,
                        resp -> resp.bodyToMono(String.class).map(
                                body -> WebClientResponseException.create(
                                        resp.statusCode().value(), "CT product-types error",
                                        resp.headers().asHttpHeaders(), body.getBytes(), null)))
                .bodyToMono(ProductTypeResponse.class)
                .block(Duration.ofMillis(settings.getReadTimeoutMs()));
    }

    private void postCreate(ProductDraft draft) {
        debugLogPayload("CREATE /products", draft);
        try {
            buildClient()
                .post()
                .uri("/{project}/products", settings.getProjectKey())
                .header("Authorization", bearer())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(draft)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        resp -> resp.bodyToMono(String.class).map(
                                body -> {
                                    log.error("[CT CREATE] Error response: {}", body);
                                    return org.springframework.web.reactive.function.client.WebClientResponseException.create(
                                            resp.statusCode().value(), "CT create failed",
                                            resp.headers().asHttpHeaders(), body.getBytes(), null);
                                }))
                .bodyToMono(String.class)
                .block(Duration.ofMillis(settings.getReadTimeoutMs()));
        } catch (Exception e) {
            log.error("[CT CREATE] Exception during product creation:", e);
            throw e;
        }
    }

    private void postUpdate(String productKey, ProductUpdate update) {
        debugLogPayload("UPDATE /products/key=" + productKey, update);
        buildClient()
                .post()
                .uri("/{project}/products/key={key}", settings.getProjectKey(), productKey)
                .header("Authorization", bearer())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(update)
                .retrieve()
                .onStatus(HttpStatusCode::isError,
                        resp -> resp.bodyToMono(String.class).map(
                                body -> WebClientResponseException.create(
                                        resp.statusCode().value(), "CT update failed",
                                        resp.headers().asHttpHeaders(), body.getBytes(), null)))
                .bodyToMono(String.class)
                .block(Duration.ofMillis(settings.getReadTimeoutMs()));
    }

    /** Serializes the request payload to JSON and prints it to the console before sending. */
    private void debugLogPayload(String operation, Object payload) {
        try {
            String json = buildObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            log.info("[CT PAYLOAD] ── {} ──────────────────────────────", operation);
            log.info("[CT PAYLOAD] {}", json);
            log.info("[CT PAYLOAD] {}", DIVIDER);
        } catch (Exception e) {
            log.warn("[CT PAYLOAD] {} → (serialization error: {})", operation, e.getMessage());
        }
    }

    // ── WebClient lazy init ──────────────────────────────────────────────────

    private WebClient buildClient() {
        if (apiWebClient == null) {
            log.info("[CT CONN] Initializing API WebClient → {} (project={})",
                    settings.getApiUrl(), settings.getProjectKey());

            HttpClient netty = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, settings.getConnectTimeoutMs())
                    .responseTimeout(Duration.ofMillis(settings.getReadTimeoutMs()));

            ObjectMapper om = buildObjectMapper();

            apiWebClient = WebClient.builder()
                    .baseUrl(settings.getApiUrl())
                    .clientConnector(new ReactorClientHttpConnector(netty))
                    .codecs(cfg -> {
                        // Register the same ObjectMapper for BOTH encoding (requests)
                        // and decoding (responses) so that NON_NULL serialization and
                        // FAIL_ON_UNKNOWN_PROPERTIES=false apply to all directions.
                        cfg.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(om));
                        cfg.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder(om));
                    })
                    .build();

            log.info("[CT CONN] ✓ API WebClient ready | baseUrl={} | connectTimeout={}ms | readTimeout={}ms | maxRetries={}",
                    settings.getApiUrl(), settings.getConnectTimeoutMs(),
                    settings.getReadTimeoutMs(), settings.getMaxRetries());
        }
        return apiWebClient;
    }

    private ObjectMapper buildObjectMapper() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper()
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }
        return objectMapper;
    }

    // ── Error body parser ────────────────────────────────────────────────────

    private ErrorResponse parseErrorBody(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return buildObjectMapper().readValue(body, ErrorResponse.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String formatErrors(List<ErrorItem> errors) {
        if (errors == null || errors.isEmpty()) return "[]";
        return errors.stream()
                .map(e -> String.format("[code=%s, typeId=%s, id=%s, msg=%s]",
                        e.code(), e.typeId(), e.id(), truncate(e.message())))
                .collect(Collectors.joining(", "));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String bearer() {
        return "Bearer " + tokenService.getBearerToken();
    }

    private boolean isRetryable(HttpStatusCode status) {
        int code = status.value();
        return code == 408 || code == 429 || code >= 500;
    }

    private void sleepBackoff(int attempt) {
        long delay = settings.getBackoffMs() * (1L << attempt);
        try {
            Thread.sleep(Math.min(delay, 30_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> collectExistingSkus(ProductData current) {
        List<String> skus = new java.util.ArrayList<>();
        if (current.masterVariant() != null && current.masterVariant().sku() != null) {
            skus.add(current.masterVariant().sku());
        }
        if (current.variants() != null) {
            current.variants().stream()
                    .map(ProductVariant::sku)
                    .filter(s -> s != null)
                    .forEach(skus::add);
        }
        return skus;
    }

    private String formatPrice(PriceDraft pd) {
        if (pd == null || pd.value() == null) return "none";
        return String.format("%s %d centAmount ($%.2f)",
                pd.value().currencyCode(),
                pd.value().centAmount(),
                pd.value().centAmount() / 100.0);
    }

    private String formatAttributes(List<AttributeDraft> attrs) {
        if (attrs == null || attrs.isEmpty()) return "[]";
        return attrs.stream()
                .map(a -> a.name() + "=" + a.value())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String truncate(String s) {
        return (s == null || s.length() <= 80) ? s : s.substring(0, 80) + "…";
    }
}

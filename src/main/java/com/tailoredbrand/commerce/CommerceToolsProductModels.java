package com.tailoredbrand.commerce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Java records mirroring the CommerceTools Products API shapes.
 *
 * <pre>
 *  GET  /{project}/products/key={key}  →  ProductResponse
 *  POST /{project}/products            →  ProductDraft (body)
 *  POST /{project}/products/key={key}  →  ProductUpdate (versioned actions body)
 * </pre>
 *
 * All field names follow the CT JSON spec; Jackson maps them via @JsonProperty where
 * the Java identifier would differ from the JSON key.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class CommerceToolsProductModels {

    private CommerceToolsProductModels() {
    }

    // ── Shared primitives ──────────────────────────────────────────────────

    /**
     * CT localized string using the {@code en-US} locale key required by this project.
     * Jackson maps the {@code enUS} component to the JSON key {@code "en-US"}.
     */
    public record LocalizedString(@JsonProperty("en-US") String enUS) {
    }

    /**
     * Reference to an existing CT resource. Exactly one of {@code key} or {@code id}
     * should be non-null — whichever is available for the resource being referenced.
     * The other will be omitted from JSON via the ObjectMapper's NON_NULL policy.
     */
    public record TypeRef(String key, String id, @JsonProperty("typeId") String typeId) {
        /** Reference by human-readable key (preferred when the product type has one). */
        public static TypeRef productType(String key) {
            return new TypeRef(key, null, "product-type");
        }
        /** Reference by system id — fallback when the product type has no key set. */
        public static TypeRef productTypeById(String id) {
            return new TypeRef(null, id, "product-type");
        }
    }

    /** CT money: centAmount = price × 100 (e.g. $62.50 → 6250). */
    public record Money(String currencyCode, long centAmount) {
        public static Money usd(double dollars) {
            return new Money("USD", Math.round(dollars * 100));
        }
    }

    /**
     * Price draft with an explicit {@code key} matching CT convention:
     * {@code {productKey}-{variantKey}-price-0}.
     */
    public record PriceDraft(String key, Money value) {
        public static PriceDraft usd(String priceKey, double dollars) {
            return new PriceDraft(priceKey, Money.usd(dollars));
        }
    }

    /** A single product attribute: name + scalar or enum value. */
    public record AttributeDraft(String name, Object value) {
    }

    // ── Variant draft (shared by create and addVariant) ────────────────────

    public record ProductVariantDraft(
            String key,
            String sku,
            List<PriceDraft> prices,
            List<AttributeDraft> attributes
    ) {
    }

    // ── POST /{project}/products  (create) ─────────────────────────────────

    /**
     * CT ProductDraft.
     *
     * <p><b>Attribute placement strategy:</b>
     * <ul>
     *   <li>{@code attributes} — product-level (shared across all variants): classCode, fit, material, etc.</li>
     *   <li>{@code masterVariant.attributes} is {@code null}/absent — variant-specific attributes
     *       (size, colour) are added via separate {@code addVariant} update actions.</li>
     * </ul>
     */
    public record ProductDraft(
            String key,
            TypeRef productType,
            LocalizedString name,
            LocalizedString slug,
            LocalizedString description,
            List<AttributeDraft> attributes,
            ProductVariantDraft masterVariant
    ) {
    }

    // ── POST /{project}/products/key={key}  (update) ───────────────────────

    public record ProductUpdate(long version, List<ProductUpdateAction> actions) {
    }

    /**
     * Generic update action container.
     * Use the typed factory methods to avoid constructing bad action objects.
     */
    public record ProductUpdateAction(
            String action,
            String key,
            String sku,
            List<PriceDraft> prices,
            List<AttributeDraft> attributes
    ) {
        /** Factory: addVariant action. */
        public static ProductUpdateAction addVariant(ProductVariantDraft v) {
            return new ProductUpdateAction("addVariant", v.key(), v.sku(), v.prices(), v.attributes());
        }
    }

    // ── GET /{project}/products/key={key}  (response) ──────────────────────

    public record ProductResponse(
            String id,
            long version,
            String key,
            MasterData masterData
    ) {
    }

    public record MasterData(ProductData current, ProductData staged, boolean published) {
    }

    public record ProductData(
            LocalizedString name,
            LocalizedString description,
            LocalizedString slug,
            ProductVariant masterVariant,
            List<ProductVariant> variants
    ) {
        /** Checks whether a variant with the given SKU already exists. */
        public boolean hasVariantWithSku(String sku) {
            if (masterVariant != null && sku.equals(masterVariant.sku())) return true;
            return variants != null && variants.stream().anyMatch(v -> sku.equals(v.sku()));
        }
    }

    public record ProductVariant(
            int id,
            String key,
            String sku,
            List<Price> prices,
            List<Attribute> attributes
    ) {
    }

    public record Price(
            String id,
            @JsonProperty("value") Money value
    ) {
    }

    public record Attribute(String name, Object value) {
    }

    // ── Error response ─────────────────────────────────────────────────────

    public record ErrorResponse(
            int statusCode,
            String message,
            List<ErrorItem> errors
    ) {
        /** Returns the first error code, or an empty string. */
        public String firstCode() {
            return (errors != null && !errors.isEmpty()) ? errors.get(0).code() : "";
        }
    }

    public record ErrorItem(
            String code,
            String message,
            String typeId,
            String id
    ) {
    }

    // ── Product-type lookup (pre-flight validation) ────────────────────────

    /** Single product-type returned by GET /{project}/product-types/key={key} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductTypeResponse(
            String id,
            String key,
            String name,
            String description,
            List<ProductTypeAttribute> attributes
    ) {
    }

    /**
     * Represents one attribute definition inside a CT product type.
     *
     * <p><b>CT schema highlights:</b>
     * <ul>
     *   <li>{@code label} is a {@link LocalizedString} object — e.g. {@code {"en":"Fit","de":"Passform"}} —
     *       not a plain string.</li>
     *   <li>{@code type} is an {@code AttributeType} object — e.g. {@code {"name":"text"}} —
     *       which we don't need, so it is ignored.</li>
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductTypeAttribute(
            String name,
            // CT returns label as LocalizedString {"en":"...","de":"..."}
            Map<String, String> label,
            @JsonProperty("isRequired") boolean isRequired
    ) {
        /** Returns the English label, falling back to the first available locale. */
        public String labelEn() {
            if (label == null || label.isEmpty()) return name;
            return label.getOrDefault("en", label.values().iterator().next());
        }
    }

    /** Paged result from GET /{project}/product-types?limit=500 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductTypesPagedQueryResponse(
            long count,
            long total,
            List<ProductTypeResponse> results
    ) {
    }
}

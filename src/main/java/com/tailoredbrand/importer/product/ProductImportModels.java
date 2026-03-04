package com.tailoredbrand.importer.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * CT Products API models for the file-upload import pipeline.
 *
 * <p>These are intentionally separate from {@code CommerceToolsProductModels}
 * because:</p>
 * <ul>
 *   <li>Localized strings are multi-locale ({@code en-GB}, {@code de-DE}) rather
 *       than the single {@code en-US} locale used by the existing Tier-1/2 pipeline.</li>
 *   <li>Prices are multi-currency (EUR, GBP) with channel references.</li>
 *   <li>Products include asset drafts.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductImportModels {

    private ProductImportModels() {}

    // ── TypeRef helpers ───────────────────────────────────────────────────────

    public record TypeRef(String key, String id, @JsonProperty("typeId") String typeId) {
        public static TypeRef byKey(String typeId, String key) {
            return new TypeRef(key, null, typeId);
        }
    }

    // ── Search keywords ───────────────────────────────────────────────────────

    public record SearchKeyword(String text) {}

    // ── Price draft (multi-currency, channel-aware) ───────────────────────────

    public record Money(String currencyCode, long centAmount) {}

    public record PriceImportDraft(
            String key,
            Money value,
            String country,
            TypeRef channel
    ) {}

    // ── Asset draft ───────────────────────────────────────────────────────────

    public record AssetSource(String uri) {}

    public record AssetDraft(
            String key,
            Map<String, String> name,
            List<AssetSource> sources
    ) {}

    // ── Variant draft ─────────────────────────────────────────────────────────

    public record AttributeDraft(String name, Object value) {}

    public record ProductImportVariantDraft(
            String key,
            String sku,
            List<AttributeDraft> attributes,
            List<AssetDraft> assets,
            List<PriceImportDraft> prices
    ) {}

    // ── Tax category reference ────────────────────────────────────────────────

    public record TaxCategoryRef(@JsonProperty("typeId") String typeId, String key) {}

    // ── Product draft ─────────────────────────────────────────────────────────

    /**
     * Full product draft sent to {@code POST /{project}/products}.
     *
     * <p><b>Note on attributes</b>: the standard CT Products REST API does NOT
     * support a top-level {@code attributes} field on the product itself.
     * All custom attributes (including {@code brand}) must reside in
     * {@code masterVariant.attributes} and {@code variants[].attributes}.
     * This differs from the CT Import API CSV format where
     * {@code productAttributes.*} columns imply a product-level concept.</p>
     */
    public record ProductImportDraft(
            String key,
            TypeRef productType,
            Map<String, String> name,
            Map<String, String> description,
            Map<String, String> slug,
            Map<String, List<SearchKeyword>> searchKeywords,
            List<TypeRef> categories,
            TaxCategoryRef taxCategory,
            ProductImportVariantDraft masterVariant,
            List<ProductImportVariantDraft> variants
    ) {}

    // ── GET response (minimal fields needed for existence check) ──────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductExistsResponse(String id, String key, Long version) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductExistsError(
            String statusCode,
            String message
    ) {}
}

package com.tailoredbrand.importer.product;

import com.tailoredbrand.importer.product.ProductImportModels.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Maps a {@link ProductImportGroup} (one or more CSV rows representing a single
 * CT product) to a {@link ProductImportDraft} ready for the CT Products API.
 *
 * <h3>Mapping rules</h3>
 * <ul>
 *   <li>Product-level fields (name, description, slug, categories, brand) come from
 *       the first {@code "Variant N"} row ({@link ProductImportGroup#header()}).</li>
 *   <li>Each distinct {@code variantsSku} in the group becomes one
 *       {@link ProductImportVariantDraft}.</li>
 *   <li>The first SKU becomes the {@code masterVariant}; the rest become
 *       {@code variants}.</li>
 *   <li>{@code "Variant N / Asset M"} rows add assets to the matching variant.</li>
 *   <li>{@code "Variant N / Price M"} rows add prices to the matching variant.</li>
 *   <li>Attribute {@code color} and {@code size} are mapped as {@code lenum} keys
 *       (plain string, not a localized object).</li>
 *   <li>Attribute {@code new-arrival} is mapped as {@code boolean}.</li>
 * </ul>
 */
@Component
@Slf4j
public class ProductImportMapper {

    public ProductImportDraft toProductDraft(ProductImportGroup group) {
        ProductImportRecord header = group.header();
        List<String> skus = group.orderedVariantSkus();

        if (skus.isEmpty()) {
            throw new IllegalArgumentException(
                    "Product group for key=" + header.key() + " has no variant SKUs");
        }

        // Build one variant draft per distinct SKU.
        // The header is passed so that product-level CSV fields (e.g. brand) can be
        // added to each variant — the CT REST API has no product-level attributes field.
        List<ProductImportVariantDraft> allVariants = skus.stream()
                .map(sku -> buildVariantDraft(sku, group.rowsForVariant(sku), header))
                .toList();

        ProductImportVariantDraft masterVariant = allVariants.get(0);
        List<ProductImportVariantDraft> additionalVariants = allVariants.subList(1, allVariants.size());

        return new ProductImportDraft(
                header.key(),
                TypeRef.byKey("product-type", header.productTypeKey()),
                buildLocalized(header.nameEnGB(), header.nameDeDE()),
                buildLocalized(header.descriptionEnGB(), null),
                buildLocalized(header.slugEnGB(), null),
                buildSearchKeywords(header.searchKeywordsEnGB()),
                buildCategories(header.categories()),
                buildTaxCategory(header.taxCategoryKey()),
                masterVariant,
                additionalVariants.isEmpty() ? null : additionalVariants
        );
    }

    // ── Variant builder ───────────────────────────────────────────────────────

    /**
     * Builds one {@link ProductImportVariantDraft} for the given SKU.
     *
     * @param header the product-level header row — used to inject {@code brand}
     *               into every variant because the CT REST API has no product-level
     *               attributes field (unlike the CT Import API CSV format)
     */
    private ProductImportVariantDraft buildVariantDraft(String sku,
                                                         List<ProductImportRecord> variantRows,
                                                         ProductImportRecord header) {
        // The first "Variant N" row for this SKU carries the variant attributes
        ProductImportRecord primary = variantRows.stream()
                .filter(ProductImportRecord::isVariantRow)
                .findFirst()
                .orElse(variantRows.get(0));

        return new ProductImportVariantDraft(
                primary.variantsKey(),
                sku,
                buildVariantAttributes(primary, header),
                buildAssets(variantRows),
                buildPrices(variantRows)
        );
    }

    // ── Attribute builders ────────────────────────────────────────────────────

    /**
     * Builds the variant-level attribute list.
     *
     * <p>{@code brand} is sourced from the product {@code header} row
     * (CSV column {@code productAttributes.brand}) and added here because
     * the CT REST API has no product-level attributes — every attribute must
     * be on the variant.</p>
     */
    private List<AttributeDraft> buildVariantAttributes(ProductImportRecord row,
                                                          ProductImportRecord header) {
        List<AttributeDraft> attrs = new ArrayList<>();

        // brand — shared across all variants of the same product (from header row)
        if (header.productAttributesBrand() != null) {
            attrs.add(new AttributeDraft("brand", header.productAttributesBrand()));
        }
        // color and size → lenum keys (plain string)
        if (row.attributesColorEnGB() != null) {
            attrs.add(new AttributeDraft("color", row.attributesColorEnGB()));
        }
        if (row.attributesSizeEnGB() != null) {
            attrs.add(new AttributeDraft("size", row.attributesSizeEnGB()));
        }
        // new-arrival → boolean
        if (row.attributesNewArrival() != null) {
            attrs.add(new AttributeDraft("new-arrival",
                    Boolean.parseBoolean(row.attributesNewArrival())));
        }
        return attrs.isEmpty() ? null : attrs;
    }

    // ── Asset builder ─────────────────────────────────────────────────────────

    private List<AssetDraft> buildAssets(List<ProductImportRecord> rows) {
        List<AssetDraft> assets = new ArrayList<>();
        for (ProductImportRecord row : rows) {
            if (row.variantsAssetsKey() == null) continue;
            assets.add(new AssetDraft(
                    row.variantsAssetsKey(),
                    row.variantsAssetsNameEn() != null
                            ? Map.of("en", row.variantsAssetsNameEn()) : null,
                    row.variantsAssetsSourcesUri() != null
                            ? List.of(new AssetSource(row.variantsAssetsSourcesUri())) : null
            ));
        }
        return assets.isEmpty() ? null : assets;
    }

    // ── Price builder ─────────────────────────────────────────────────────────

    private List<PriceImportDraft> buildPrices(List<ProductImportRecord> rows) {
        List<PriceImportDraft> prices = new ArrayList<>();
        for (ProductImportRecord row : rows) {
            if (row.variantsPricesCurrencyCode() == null
                    || row.variantsPricesCentAmount() == null) continue;
            try {
                long centAmount = Long.parseLong(row.variantsPricesCentAmount().trim());
                TypeRef channel = null;
                if (row.variantsPricesChannelKey() != null) {
                    channel = TypeRef.byKey(
                            row.variantsPricesChannelTypeId() != null
                                    ? row.variantsPricesChannelTypeId() : "channel",
                            row.variantsPricesChannelKey());
                }
                prices.add(new PriceImportDraft(
                        row.variantsPricesKey(),
                        new Money(row.variantsPricesCurrencyCode(), centAmount),
                        row.variantsPricesCountry(),
                        channel
                ));
            } catch (NumberFormatException e) {
                log.warn("[PRODUCT MAPPER] Skipping malformed centAmount '{}' for priceKey={}",
                        row.variantsPricesCentAmount(), row.variantsPricesKey());
            }
        }
        return prices.isEmpty() ? null : prices;
    }

    // ── Localized string helpers ──────────────────────────────────────────────

    private Map<String, String> buildLocalized(String enGB, String deDE) {
        Map<String, String> map = new LinkedHashMap<>();
        if (enGB != null && !enGB.isBlank()) map.put("en-GB", enGB);
        if (deDE != null && !deDE.isBlank()) map.put("de-DE", deDE);
        return map.isEmpty() ? null : map;
    }

    // ── Search keywords ───────────────────────────────────────────────────────

    private Map<String, List<SearchKeyword>> buildSearchKeywords(String raw) {
        if (raw == null || raw.isBlank()) return null;
        List<SearchKeyword> keywords = Arrays.stream(raw.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(SearchKeyword::new)
                .toList();
        return keywords.isEmpty() ? null : Map.of("en-GB", keywords);
    }

    // ── Category references ───────────────────────────────────────────────────

    private List<TypeRef> buildCategories(String raw) {
        if (raw == null || raw.isBlank()) return null;
        List<TypeRef> refs = Arrays.stream(raw.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(key -> TypeRef.byKey("category", key))
                .toList();
        return refs.isEmpty() ? null : refs;
    }

    // ── Tax category ──────────────────────────────────────────────────────────

    private TaxCategoryRef buildTaxCategory(String key) {
        return key != null && !key.isBlank() ? new TaxCategoryRef("tax-category", key) : null;
    }
}

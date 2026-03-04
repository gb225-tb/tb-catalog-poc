package com.tailoredbrand.importer.product;

/**
 * One raw row from the CT-format product import CSV.
 *
 * <p>All 32 columns are kept as plain {@code String}s; type conversion is
 * handled later in {@link ProductImportMapper}.</p>
 *
 * <h3>Column order matches the template header exactly:</h3>
 * <pre>
 * 0  data-object
 * 1  variants.sku
 * 2  productType.key
 * 3  productType.typeId
 * 4  key
 * 5  name.en-GB
 * 6  name.de-DE
 * 7  description.en-GB
 * 8  categories
 * 9  searchKeywords.en-GB
 * 10 slug.en-GB
 * 11 metaTitle.en-GB
 * 12 metaDescription.en-GB
 * 13 taxCategory.key
 * 14 taxCategory.typeId
 * 15 priceMode
 * 16 productAttributes.brand
 * 17 variants.key
 * 18 attributes.color.en-GB
 * 19 attributes.size.en-GB
 * 20 attributes.new-arrival
 * 21 variants.assets.key
 * 22 variants.assets.name.en
 * 23 variants.assets.sources.uri
 * 24 variants.prices.key
 * 25 variants.prices.value.currencyCode
 * 26 variants.prices.value.centAmount
 * 27 variants.prices.value.fractionDigits
 * 28 variants.prices.value.type
 * 29 variants.prices.country
 * 30 variants.prices.channel.key
 * 31 variants.prices.channel.typeId
 * </pre>
 */
public record ProductImportRecord(
        // Row discriminator
        String dataObject,

        // Variant identity
        String variantsSku,
        String variantsKey,

        // Product-level (populated only on the first "Variant N" row)
        String productTypeKey,
        String productTypeTypeId,
        String key,
        String nameEnGB,
        String nameDeDE,
        String descriptionEnGB,
        String categories,
        String searchKeywordsEnGB,
        String slugEnGB,
        String metaTitleEnGB,
        String metaDescriptionEnGB,
        String taxCategoryKey,
        String taxCategoryTypeId,
        String priceMode,
        String productAttributesBrand,

        // Variant attributes (populated on "Variant N" rows)
        String attributesColorEnGB,
        String attributesSizeEnGB,
        String attributesNewArrival,

        // Asset (populated on "Variant N" and "Variant N / Asset M" rows)
        String variantsAssetsKey,
        String variantsAssetsNameEn,
        String variantsAssetsSourcesUri,

        // Price (populated on "Variant N / Price M" rows and optionally on "Variant N")
        String variantsPricesKey,
        String variantsPricesCurrencyCode,
        String variantsPricesCentAmount,
        String variantsPricesFractionDigits,
        String variantsPricesType,
        String variantsPricesCountry,
        String variantsPricesChannelKey,
        String variantsPricesChannelTypeId
) {

    /**
     * Returns {@code true} when this row introduces a new variant
     * (e.g. {@code "Variant 1"}, {@code "Variant 2"}).
     */
    public boolean isVariantRow() {
        return dataObject != null
                && dataObject.matches("Variant \\d+")
                && !dataObject.contains("/");
    }

    /**
     * Returns {@code true} when this row adds an extra asset to an existing variant
     * (e.g. {@code "Variant 1 / Asset 2"}).
     */
    public boolean isAssetRow() {
        return dataObject != null && dataObject.contains("/ Asset");
    }

    /**
     * Returns {@code true} when this row adds an extra price to an existing variant
     * (e.g. {@code "Variant 2 / Price 2"}).
     */
    public boolean isPriceRow() {
        return dataObject != null && dataObject.contains("/ Price");
    }

    /** Parses a raw CSV line (comma-separated, 32 columns) into a {@link ProductImportRecord}. */
    public static ProductImportRecord fromCsvColumns(String[] cols) {
        return new ProductImportRecord(
                get(cols, 0),   // dataObject
                get(cols, 1),   // variantsSku
                get(cols, 17),  // variantsKey
                get(cols, 2),   // productTypeKey
                get(cols, 3),   // productTypeTypeId
                get(cols, 4),   // key
                get(cols, 5),   // nameEnGB
                get(cols, 6),   // nameDeDE
                get(cols, 7),   // descriptionEnGB
                get(cols, 8),   // categories
                get(cols, 9),   // searchKeywordsEnGB
                get(cols, 10),  // slugEnGB
                get(cols, 11),  // metaTitleEnGB
                get(cols, 12),  // metaDescriptionEnGB
                get(cols, 13),  // taxCategoryKey
                get(cols, 14),  // taxCategoryTypeId
                get(cols, 15),  // priceMode
                get(cols, 16),  // productAttributesBrand
                get(cols, 18),  // attributesColorEnGB
                get(cols, 19),  // attributesSizeEnGB
                get(cols, 20),  // attributesNewArrival
                get(cols, 21),  // variantsAssetsKey
                get(cols, 22),  // variantsAssetsNameEn
                get(cols, 23),  // variantsAssetsSourcesUri
                get(cols, 24),  // variantsPricesKey
                get(cols, 25),  // variantsPricesCurrencyCode
                get(cols, 26),  // variantsPricesCentAmount
                get(cols, 27),  // variantsPricesFractionDigits
                get(cols, 28),  // variantsPricesType
                get(cols, 29),  // variantsPricesCountry
                get(cols, 30),  // variantsPricesChannelKey
                get(cols, 31)   // variantsPricesChannelTypeId
        );
    }

    private static String get(String[] cols, int idx) {
        if (idx >= cols.length) return null;
        String v = cols[idx].trim();
        return v.isEmpty() ? null : v;
    }
}

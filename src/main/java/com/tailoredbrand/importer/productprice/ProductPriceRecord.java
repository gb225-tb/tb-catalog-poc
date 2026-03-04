package com.tailoredbrand.importer.productprice;

/**
 * One raw row from the product price import CSV (19 columns).
 *
 * <h3>Column order</h3>
 * <pre>
 *  0  variants.sku
 *  1  key                                         (product key)
 *  2  variants.key
 *  3  variants.prices.key
 *  4  variants.prices.value.currencyCode
 *  5  variants.prices.value.centAmount
 *  6  variants.prices.value.preciseAmount
 *  7  variants.prices.value.fractionDigits
 *  8  variants.prices.value.type                  (centPrecision | highPrecision)
 *  9  variants.prices.country
 * 10  variants.prices.channel.key
 * 11  variants.prices.channel.typeId
 * 12  variants.prices.validFrom
 * 13  variants.prices.validUntil
 * 14  variants.prices.custom.type.key
 * 15  variants.prices.custom.type.typeId
 * 16  variants.prices.custom.fields.discounted
 * 17  variants.prices.custom.fields.banner-title.en
 * 18  variants.prices.custom.fields.banner-title.de
 * </pre>
 */
public record ProductPriceRecord(
        String variantSku,
        String productKey,
        String variantKey,
        String priceKey,
        String currencyCode,
        String centAmount,
        String preciseAmount,
        String fractionDigits,
        String type,
        String country,
        String channelKey,
        String channelTypeId,
        String validFrom,
        String validUntil,
        String customTypeKey,
        String customTypeTypeId,
        String customDiscounted,
        String customBannerTitleEn,
        String customBannerTitleDe
) {
    public static ProductPriceRecord fromCsvColumns(String[] cols) {
        return new ProductPriceRecord(
                get(cols, 0),  get(cols, 1),  get(cols, 2),  get(cols, 3),
                get(cols, 4),  get(cols, 5),  get(cols, 6),  get(cols, 7),
                get(cols, 8),  get(cols, 9),  get(cols, 10), get(cols, 11),
                get(cols, 12), get(cols, 13), get(cols, 14), get(cols, 15),
                get(cols, 16), get(cols, 17), get(cols, 18)
        );
    }

    private static String get(String[] cols, int idx) {
        if (idx >= cols.length) return null;
        String v = cols[idx].trim();
        return v.isEmpty() ? null : v;
    }
}

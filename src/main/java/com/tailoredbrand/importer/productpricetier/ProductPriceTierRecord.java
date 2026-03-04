package com.tailoredbrand.importer.productpricetier;

/**
 * One raw row from the product price-tiers import CSV (9 columns).
 *
 * <h3>Column order</h3>
 * <pre>
 *  0  variants.sku
 *  1  key                                         (product key — blank on continuation rows)
 *  2  variants.key
 *  3  variants.prices.key                         (price key — blank on continuation rows)
 *  4  variants.prices.tiers.minimumQuantity
 *  5  variants.prices.tiers.value.centAmount
 *  6  variants.prices.tiers.value.currencyCode
 *  7  variants.prices.tiers.value.type
 *  8  variants.prices.tiers.value.fractionDigits
 * </pre>
 *
 * <h3>Row classification</h3>
 * <ul>
 *   <li>{@link #isNewPriceRow()} — {@code priceKey} (col 3) is non-blank → starts a new
 *       price-tier group.</li>
 *   <li>{@link #isContinuationRow()} — {@code priceKey} blank, {@code minimumQuantity}
 *       non-blank → additional tier for the current price group.</li>
 * </ul>
 */
public record ProductPriceTierRecord(
        String variantSku,
        String productKey,
        String variantKey,
        String priceKey,
        String minimumQuantity,
        String centAmount,
        String currencyCode,
        String type,
        String fractionDigits
) {
    public boolean isNewPriceRow() {
        return priceKey != null && !priceKey.isBlank();
    }

    public boolean isContinuationRow() {
        return (priceKey == null || priceKey.isBlank())
                && minimumQuantity != null && !minimumQuantity.isBlank();
    }

    public static ProductPriceTierRecord fromCsvColumns(String[] cols) {
        return new ProductPriceTierRecord(
                get(cols, 0), get(cols, 1), get(cols, 2), get(cols, 3),
                get(cols, 4), get(cols, 5), get(cols, 6), get(cols, 7),
                get(cols, 8)
        );
    }

    private static String get(String[] cols, int idx) {
        if (idx >= cols.length) return null;
        String v = cols[idx].trim();
        return v.isEmpty() ? null : v;
    }
}

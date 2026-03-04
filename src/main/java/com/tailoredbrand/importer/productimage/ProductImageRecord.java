package com.tailoredbrand.importer.productimage;

/**
 * One raw row from the product image import CSV (7 columns).
 *
 * <h3>Column order</h3>
 * <pre>
 *  0  variants.sku
 *  1  key                        (product key)
 *  2  variants.key
 *  3  variants.images.url
 *  4  variants.images.label
 *  5  variants.images.dimensions.w
 *  6  variants.images.dimensions.h
 * </pre>
 */
public record ProductImageRecord(
        String variantSku,
        String productKey,
        String variantKey,
        String imageUrl,
        String imageLabel,
        String dimensionW,
        String dimensionH
) {
    public static ProductImageRecord fromCsvColumns(String[] cols) {
        return new ProductImageRecord(
                get(cols, 0), get(cols, 1), get(cols, 2), get(cols, 3),
                get(cols, 4), get(cols, 5), get(cols, 6)
        );
    }

    private static String get(String[] cols, int idx) {
        if (idx >= cols.length) return null;
        String v = cols[idx].trim();
        return v.isEmpty() ? null : v;
    }
}

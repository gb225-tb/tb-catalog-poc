package com.tailoredbrand.importer.product;

import java.util.List;

/**
 * Aggregated representation of one CT product from the multi-row CSV.
 *
 * <p>The {@code header} is the first {@code "Variant N"} row that carries the
 * non-blank {@code key} column.  {@code allRows} contains every row that
 * belongs to the same product (header + any {@code "Variant N / Asset M"} and
 * {@code "Variant N / Price M"} continuation rows).</p>
 *
 * <p>Grouping rule: a new product group starts whenever a row has a non-blank
 * {@code key} value.  All subsequent rows with a blank {@code key} belong to
 * the same group.</p>
 */
public record ProductImportGroup(
        ProductImportRecord header,
        List<ProductImportRecord> allRows
) {

    /** Convenience: returns all rows whose {@code variantsSku} matches {@code sku}. */
    public List<ProductImportRecord> rowsForVariant(String sku) {
        return allRows.stream()
                .filter(r -> sku.equals(r.variantsSku()))
                .toList();
    }

    /**
     * Returns the distinct variant SKUs in the order they first appear.
     * The first SKU will become the masterVariant.
     */
    public List<String> orderedVariantSkus() {
        return allRows.stream()
                .map(ProductImportRecord::variantsSku)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
    }
}

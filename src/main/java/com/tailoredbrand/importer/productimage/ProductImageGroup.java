package com.tailoredbrand.importer.productimage;

import java.util.List;

/**
 * All image rows belonging to one CT product, keyed by the product's {@code key}.
 * Multiple variants (different {@code variantSku} values) may exist within the same group.
 */
public record ProductImageGroup(
        String productKey,
        List<ProductImageRecord> imageRows
) {}

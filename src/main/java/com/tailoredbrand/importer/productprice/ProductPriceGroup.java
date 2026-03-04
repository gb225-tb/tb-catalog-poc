package com.tailoredbrand.importer.productprice;

import java.util.List;

/**
 * All price rows belonging to one CT product, keyed by the product's {@code key}.
 * Each row in {@code priceRows} represents one standalone price to add or update.
 */
public record ProductPriceGroup(
        String productKey,
        List<ProductPriceRecord> priceRows
) {}

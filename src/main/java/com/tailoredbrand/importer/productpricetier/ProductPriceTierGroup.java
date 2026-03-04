package com.tailoredbrand.importer.productpricetier;

import java.util.List;

/**
 * All tier rows for one specific price, identified by its {@code priceKey}.
 *
 * <p>The {@code productKey} and {@code sku} are taken from the first (header) row of
 * the group and are used to locate the correct product and variant when searching
 * for the existing price to update.</p>
 */
public record ProductPriceTierGroup(
        String productKey,
        String priceKey,
        String sku,
        List<ProductPriceTierRecord> tiers
) {}

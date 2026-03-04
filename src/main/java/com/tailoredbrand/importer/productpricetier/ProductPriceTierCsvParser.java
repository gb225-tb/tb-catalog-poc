package com.tailoredbrand.importer.productpricetier;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the product price-tiers import CSV into {@link ProductPriceTierGroup}s.
 *
 * <p>A new group starts whenever {@code variants.prices.key} (col 3) is non-blank.
 * Continuation rows (blank price key, non-blank {@code minimumQuantity}) are appended
 * to the current group as additional tiers.</p>
 *
 * <p>Both the header row and continuation rows are included in
 * {@link ProductPriceTierGroup#tiers()} so the service can build the full tier list
 * in one pass.</p>
 */
@Component
public class ProductPriceTierCsvParser {

    public List<ProductPriceTierGroup> parse(InputStream csvStream) throws IOException {
        List<ProductPriceTierGroup> groups = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream))) {
            String line;
            boolean firstLine = true;
            String currentProductKey = null;
            String currentPriceKey   = null;
            String currentSku        = null;
            List<ProductPriceTierRecord> currentTiers = new ArrayList<>();

            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                if (line.isBlank()) continue;

                ProductPriceTierRecord row = ProductPriceTierRecord.fromCsvColumns(line.split(",", -1));

                if (row.isNewPriceRow()) {
                    if (currentPriceKey != null) {
                        groups.add(new ProductPriceTierGroup(
                                currentProductKey, currentPriceKey, currentSku,
                                List.copyOf(currentTiers)));
                    }
                    currentProductKey = row.productKey();
                    currentPriceKey   = row.priceKey();
                    currentSku        = row.variantSku();
                    currentTiers      = new ArrayList<>();
                    currentTiers.add(row);

                } else if (row.isContinuationRow() && currentPriceKey != null) {
                    currentTiers.add(row);
                }
            }

            if (currentPriceKey != null) {
                groups.add(new ProductPriceTierGroup(
                        currentProductKey, currentPriceKey, currentSku,
                        List.copyOf(currentTiers)));
            }
        }

        return groups;
    }
}

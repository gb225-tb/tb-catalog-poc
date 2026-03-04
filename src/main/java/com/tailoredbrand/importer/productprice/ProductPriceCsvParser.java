package com.tailoredbrand.importer.productprice;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Parses the product price import CSV into {@link ProductPriceGroup}s.
 * Every data row carries a non-blank product {@code key}, so grouping is done
 * by that column while preserving insertion order.
 */
@Component
public class ProductPriceCsvParser {

    public List<ProductPriceGroup> parse(InputStream csvStream) throws IOException {
        Map<String, List<ProductPriceRecord>> grouped = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                if (line.isBlank()) continue;

                ProductPriceRecord rec = ProductPriceRecord.fromCsvColumns(line.split(",", -1));
                if (rec.productKey() != null) {
                    grouped.computeIfAbsent(rec.productKey(), k -> new ArrayList<>()).add(rec);
                }
            }
        }

        return grouped.entrySet().stream()
                .map(e -> new ProductPriceGroup(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }
}

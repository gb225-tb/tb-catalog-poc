package com.tailoredbrand.importer.productimage;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Parses the product image import CSV into {@link ProductImageGroup}s.
 * All rows in the template carry a non-blank {@code key} (product key), so grouping
 * is simply done by that column while preserving row order.
 */
@Component
public class ProductImageCsvParser {

    public List<ProductImageGroup> parse(InputStream csvStream) throws IOException {
        Map<String, List<ProductImageRecord>> grouped = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                if (line.isBlank()) continue;

                ProductImageRecord rec = ProductImageRecord.fromCsvColumns(line.split(",", -1));
                if (rec.productKey() != null) {
                    grouped.computeIfAbsent(rec.productKey(), k -> new ArrayList<>()).add(rec);
                }
            }
        }

        return grouped.entrySet().stream()
                .map(e -> new ProductImageGroup(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }
}

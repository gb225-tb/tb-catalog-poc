package com.tailoredbrand.importer.category;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses the CT category import CSV into {@link CategoryImportGroup}s.
 *
 * <p>Groups are sorted so that top-level categories (no {@code parent.key}) come
 * before child categories, satisfying CT's parent-must-exist-first constraint.</p>
 */
@Component
public class CategoryImportCsvParser {

    public List<CategoryImportGroup> parse(InputStream csvStream) throws IOException {
        List<CategoryImportGroup> groups = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(csvStream))) {
            String line;
            boolean firstLine = true;
            CategoryImportRecord currentHeader = null;
            List<CategoryImportRecord> currentAssets = new ArrayList<>();

            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                if (line.isBlank()) continue;

                String[] cols = line.split(",", -1);
                CategoryImportRecord row = CategoryImportRecord.fromCsvColumns(cols);

                if (row.isNewCategoryRow()) {
                    if (currentHeader != null) {
                        groups.add(new CategoryImportGroup(currentHeader, List.copyOf(currentAssets)));
                    }
                    currentHeader = row;
                    currentAssets = new ArrayList<>();
                    if (row.hasAsset()) {
                        currentAssets.add(row);
                    }
                } else if (row.isAssetContinuationRow() && currentHeader != null) {
                    currentAssets.add(row);
                }
            }

            if (currentHeader != null) {
                groups.add(new CategoryImportGroup(currentHeader, List.copyOf(currentAssets)));
            }
        }

        // Sort: categories with no parent first so parents are created before children
        groups.sort(Comparator.comparingInt(g -> g.hasParent() ? 1 : 0));
        return groups;
    }
}

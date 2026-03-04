package com.tailoredbrand.importer.category;

import java.util.List;

/**
 * Aggregated representation of one CommerceTools Category from the multi-row CSV.
 *
 * <p>The {@code header} row is the one with a non-blank {@code key}.
 * {@code assetRows} are the continuation rows (blank key, non-blank {@code assets.key})
 * belonging to this category, plus the header row itself if it also contains asset data.</p>
 */
public record CategoryImportGroup(
        CategoryImportRecord header,
        List<CategoryImportRecord> assetRows
) {
    /** Returns {@code true} if this category has a parent category defined. */
    public boolean hasParent() {
        return header.parentKey() != null && !header.parentKey().isBlank();
    }
}

package com.tailoredbrand.importer.businessunit;

import java.util.List;

/**
 * Aggregated representation of one CommerceTools Business Unit from the
 * multi-row CSV.
 *
 * <p>The {@code header} row is the one with a non-blank {@code key} column.
 * {@code associateRows} are all blank-key rows that carry additional associate
 * data.  {@code addressRows} are all blank-key rows that carry additional address
 * data for this BU (beyond the first address included in the header row).</p>
 *
 * <p>Note: the header row itself may already contain the <em>first</em> associate
 * and the <em>first</em> address — these are not duplicated in the extension
 * lists but ARE included if the header row qualifies.</p>
 */
public record BuImportGroup(
        BuImportRecord header,
        List<BuImportRecord> associateRows,
        List<BuImportRecord> addressRows
) {

    /** Returns {@code true} if this BU is a Division (has a parentUnit). */
    public boolean isDivision() {
        return "Division".equalsIgnoreCase(header.unitType());
    }
}

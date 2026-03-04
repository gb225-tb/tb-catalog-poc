package com.tailoredbrand.importer.businessunit;

/**
 * One raw row from the Business Unit import CSV (34 columns).
 *
 * <h3>Column order</h3>
 * <pre>
 *  0  key
 *  1  name
 *  2  status
 *  3  unitType
 *  4  parentUnit.key
 *  5  storeMode
 *  6  stores
 *  7  associateMode
 *  8  approvalRuleMode
 *  9  contactEmail
 * 10  shippingAddressKeys
 * 11  defaultShippingAddressKey
 * 12  billingAddressKeys
 * 13  defaultBillingAddressKey
 * 14  associates.associateRoleAssignments.associateRole.key
 * 15  associates.associateRoleAssignments.inheritance
 * 16  associates.customer.key
 * 17  addresses.key
 * 18  addresses.country
 * 19  addresses.company
 * 20  addresses.streetName
 * 21  addresses.streetNumber
 * 22  addresses.building
 * 23  addresses.pOBox
 * 24  addresses.apartment
 * 25  addresses.city
 * 26  addresses.postalCode
 * 27  addresses.region
 * 28  addresses.state
 * 29  addresses.additionalStreetInfo
 * 30  addresses.custom.type.key
 * 31  addresses.custom.fields.time-zone
 * 32  custom.type.key
 * 33  custom.fields.employee-count
 * </pre>
 *
 * <h3>Row classification</h3>
 * <ul>
 *   <li>{@link #isNewBuRow()} — column 0 ({@code key}) is non-blank → new BU starts here.</li>
 *   <li>{@link #isAssociateContinuationRow()} — column 0 blank, column 14 non-blank →
 *       additional associate for the current BU.</li>
 *   <li>{@link #isAddressContinuationRow()} — column 0 blank, column 17 non-blank →
 *       additional address for the current BU.</li>
 * </ul>
 */
public record BuImportRecord(
        // BU identity
        String key,
        String name,
        String status,
        String unitType,
        String parentUnitKey,
        String storeMode,
        String stores,
        String associateMode,
        String approvalRuleMode,
        String contactEmail,
        String shippingAddressKeys,
        String defaultShippingAddressKey,
        String billingAddressKeys,
        String defaultBillingAddressKey,

        // Associate
        String associateRoleKey,
        String associateRoleInheritance,
        String associateCustomerKey,

        // Address
        String addressKey,
        String addressCountry,
        String addressCompany,
        String addressStreetName,
        String addressStreetNumber,
        String addressBuilding,
        String addressPOBox,
        String addressApartment,
        String addressCity,
        String addressPostalCode,
        String addressRegion,
        String addressState,
        String addressAdditionalStreetInfo,
        String addressCustomTypeKey,
        String addressCustomTimeZone,

        // BU custom
        String customTypeKey,
        String customEmployeeCount
) {

    public boolean isNewBuRow() {
        return key != null && !key.isBlank();
    }

    public boolean isAssociateContinuationRow() {
        return (key == null || key.isBlank())
                && associateRoleKey != null && !associateRoleKey.isBlank();
    }

    public boolean isAddressContinuationRow() {
        return (key == null || key.isBlank())
                && addressKey != null && !addressKey.isBlank();
    }

    public boolean hasAssociate() {
        return associateRoleKey != null && !associateRoleKey.isBlank();
    }

    public boolean hasAddress() {
        return addressKey != null && !addressKey.isBlank();
    }

    /** Parses a raw CSV line (comma-separated, 34 columns) into a {@link BuImportRecord}. */
    public static BuImportRecord fromCsvColumns(String[] cols) {
        return new BuImportRecord(
                get(cols, 0),  get(cols, 1),  get(cols, 2),  get(cols, 3),
                get(cols, 4),  get(cols, 5),  get(cols, 6),  get(cols, 7),
                get(cols, 8),  get(cols, 9),  get(cols, 10), get(cols, 11),
                get(cols, 12), get(cols, 13), get(cols, 14), get(cols, 15),
                get(cols, 16), get(cols, 17), get(cols, 18), get(cols, 19),
                get(cols, 20), get(cols, 21), get(cols, 22), get(cols, 23),
                get(cols, 24), get(cols, 25), get(cols, 26), get(cols, 27),
                get(cols, 28), get(cols, 29), get(cols, 30), get(cols, 31),
                get(cols, 32), get(cols, 33)
        );
    }

    private static String get(String[] cols, int idx) {
        if (idx >= cols.length) return null;
        String v = cols[idx].trim();
        return v.isEmpty() ? null : v;
    }
}

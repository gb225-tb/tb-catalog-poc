package com.tailoredbrand.importer.discount;

/**
 * One raw row from the discount code import CSV (14 columns).
 *
 * <h3>Column order</h3>
 * <pre>
 *  0  key
 *  1  name.en-GB
 *  2  code
 *  3  cartDiscounts  (semicolon-separated cart-discount keys)
 *  4  isActive
 *  5  validFrom      (ISO date, e.g. 2024-05-22)
 *  6  validUntil     (ISO date)
 *  7  maxApplications
 *  8  maxApplicationsPerCustomer
 *  9  groups         (semicolon-separated)
 * 10  custom.type.key
 * 11  custom.type.typeId
 * 12  custom.fields.date-time-field
 * 13  custom.fields.boolean-field
 * </pre>
 */
public record DiscountCodeRecord(
        String key,
        String nameEnGb,
        String code,
        String cartDiscounts,
        String isActive,
        String validFrom,
        String validUntil,
        String maxApplications,
        String maxApplicationsPerCustomer,
        String groups,
        String customTypeKey,
        String customTypeTypeId,
        String customDateTimeField,
        String customBooleanField
) {
    public static DiscountCodeRecord fromCsvColumns(String[] cols) {
        return new DiscountCodeRecord(
                get(cols, 0),  get(cols, 1),  get(cols, 2),  get(cols, 3),
                get(cols, 4),  get(cols, 5),  get(cols, 6),  get(cols, 7),
                get(cols, 8),  get(cols, 9),  get(cols, 10), get(cols, 11),
                get(cols, 12), get(cols, 13)
        );
    }

    private static String get(String[] cols, int idx) {
        if (idx >= cols.length) return null;
        String v = cols[idx].trim();
        return v.isEmpty() ? null : v;
    }
}

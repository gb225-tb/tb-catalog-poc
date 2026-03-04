package com.tailoredbrand.importer.inventory;

/**
 * One raw row from the inventory entry import CSV (21 columns).
 *
 * <h3>Column order</h3>
 * <pre>
 *  0  sku
 *  1  key
 *  2  quantityOnStock
 *  3  restockableInDays
 *  4  supplyChannel.key
 *  5  supplyChannel.typeId
 *  6  custom.type.key
 *  7  custom.type.typeId
 *  8  custom.fields.boolean-field
 *  9  custom.fields.date-field
 * 10  custom.fields.date-time-field
 * 11  custom.fields.enum-field
 * 12  custom.fields.localized-enum-field
 * 13  custom.fields.localized-string-field.en
 * 14  custom.fields.number-field
 * 15  custom.fields.string-field
 * 16  custom.fields.time-field
 * 17  custom.fields.money-field.currencyCode
 * 18  custom.fields.money-field.centAmount
 * 19  custom.fields.money-field.type
 * 20  custom.fields.money-field.fractionDigits
 * </pre>
 */
public record InventoryRecord(
        String sku,
        String key,
        String quantityOnStock,
        String restockableInDays,
        String supplyChannelKey,
        String supplyChannelTypeId,
        String customTypeKey,
        String customTypeTypeId,
        String customBooleanField,
        String customDateField,
        String customDateTimeField,
        String customEnumField,
        String customLocalizedEnumField,
        String customLocalizedStringFieldEn,
        String customNumberField,
        String customStringField,
        String customTimeField,
        String customMoneyFieldCurrencyCode,
        String customMoneyFieldCentAmount,
        String customMoneyFieldType,
        String customMoneyFieldFractionDigits
) {
    public static InventoryRecord fromCsvColumns(String[] cols) {
        return new InventoryRecord(
                get(cols, 0),  get(cols, 1),  get(cols, 2),  get(cols, 3),
                get(cols, 4),  get(cols, 5),  get(cols, 6),  get(cols, 7),
                get(cols, 8),  get(cols, 9),  get(cols, 10), get(cols, 11),
                get(cols, 12), get(cols, 13), get(cols, 14), get(cols, 15),
                get(cols, 16), get(cols, 17), get(cols, 18), get(cols, 19),
                get(cols, 20)
        );
    }

    private static String get(String[] cols, int idx) {
        if (idx >= cols.length) return null;
        String v = cols[idx].trim();
        return v.isEmpty() ? null : v;
    }
}

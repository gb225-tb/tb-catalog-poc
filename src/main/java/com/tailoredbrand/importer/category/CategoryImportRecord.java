package com.tailoredbrand.importer.category;

/**
 * One raw row from the category import CSV (31 columns).
 *
 * <h3>Column order</h3>
 * <pre>
 *  0  data-object
 *  1  key
 *  2  name.en
 *  3  slug.en
 *  4  description.en
 *  5  description.de-DE
 *  6  externalId
 *  7  parent.key
 *  8  parent.typeId
 *  9  orderHint
 * 10  metaTitle.en
 * 11  metaDescription.en
 * 12  metaKeywords.en
 * 13  custom.type.key
 * 14  custom.type.typeId
 * 15  custom.fields.boolean-field
 * 16  custom.fields.string-field
 * 17  custom.fields.localized-string-field.en
 * 18  custom.fields.money-field.currencyCode
 * 19  custom.fields.money-field.centAmount
 * 20  custom.fields.money-field.type
 * 21  custom.fields.money-field.fractionDigits
 * 22  custom.fields.enum-field
 * 23  custom.fields.date-field
 * 24  custom.fields.time-field
 * 25  custom.fields.date-time-field
 * 26  assets.key
 * 27  assets.name.en
 * 28  assets.sources.uri
 * 29  assets.description.en
 * 30  assets.tags  (semicolon-separated)
 * </pre>
 *
 * <h3>Row classification</h3>
 * <ul>
 *   <li>{@link #isNewCategoryRow()} — column 1 ({@code key}) is non-blank → new category.</li>
 *   <li>{@link #isAssetContinuationRow()} — column 1 blank, column 26 ({@code assets.key})
 *       non-blank → additional asset for the current category.</li>
 * </ul>
 */
public record CategoryImportRecord(
        String dataObject,
        String key,
        String nameEn,
        String slugEn,
        String descriptionEn,
        String descriptionDeDe,
        String externalId,
        String parentKey,
        String parentTypeId,
        String orderHint,
        String metaTitleEn,
        String metaDescriptionEn,
        String metaKeywordsEn,
        String customTypeKey,
        String customTypeTypeId,
        String customBooleanField,
        String customStringField,
        String customLocalizedStringFieldEn,
        String customMoneyFieldCurrencyCode,
        String customMoneyFieldCentAmount,
        String customMoneyFieldType,
        String customMoneyFieldFractionDigits,
        String customEnumField,
        String customDateField,
        String customTimeField,
        String customDateTimeField,
        String assetKey,
        String assetNameEn,
        String assetSourcesUri,
        String assetDescriptionEn,
        String assetTags
) {
    public boolean isNewCategoryRow() {
        return key != null && !key.isBlank();
    }

    public boolean hasAsset() {
        return assetKey != null && !assetKey.isBlank();
    }

    public boolean isAssetContinuationRow() {
        return (key == null || key.isBlank()) && hasAsset();
    }

    public static CategoryImportRecord fromCsvColumns(String[] cols) {
        return new CategoryImportRecord(
                get(cols, 0),  get(cols, 1),  get(cols, 2),  get(cols, 3),
                get(cols, 4),  get(cols, 5),  get(cols, 6),  get(cols, 7),
                get(cols, 8),  get(cols, 9),  get(cols, 10), get(cols, 11),
                get(cols, 12), get(cols, 13), get(cols, 14), get(cols, 15),
                get(cols, 16), get(cols, 17), get(cols, 18), get(cols, 19),
                get(cols, 20), get(cols, 21), get(cols, 22), get(cols, 23),
                get(cols, 24), get(cols, 25), get(cols, 26), get(cols, 27),
                get(cols, 28), get(cols, 29), get(cols, 30)
        );
    }

    private static String get(String[] cols, int idx) {
        if (idx >= cols.length) return null;
        String v = cols[idx].trim();
        return v.isEmpty() ? null : v;
    }
}

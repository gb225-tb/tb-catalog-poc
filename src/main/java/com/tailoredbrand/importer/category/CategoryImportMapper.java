package com.tailoredbrand.importer.category;

import com.tailoredbrand.importer.category.CategoryImportModels.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Maps a {@link CategoryImportGroup} to a {@link CategoryDraft} ready to POST
 * to the CommerceTools Categories API.
 */
@Component
public class CategoryImportMapper {

    public CategoryDraft toCategoryDraft(CategoryImportGroup group) {
        CategoryImportRecord h = group.header();

        return new CategoryDraft(
                h.key(),
                localized("en", h.nameEn()),
                localized("en", h.slugEn()),
                localizedMulti(h.descriptionEn(), h.descriptionDeDe()),
                h.parentKey() != null ? new ResourceIdentifier("category", h.parentKey()) : null,
                h.orderHint(),
                h.externalId(),
                localized("en", h.metaTitleEn()),
                localized("en", h.metaDescriptionEn()),
                localized("en", h.metaKeywordsEn()),
                buildCustomFields(h),
                buildAssets(group.assetRows())
        );
    }

    // ── Custom fields ─────────────────────────────────────────────────────────

    private CustomFields buildCustomFields(CategoryImportRecord h) {
        if (h.customTypeKey() == null) return null;

        Map<String, Object> fields = new LinkedHashMap<>();
        if (h.customBooleanField() != null)
            fields.put("boolean-field", Boolean.parseBoolean(h.customBooleanField()));
        if (h.customStringField() != null)
            fields.put("string-field", h.customStringField());
        if (h.customLocalizedStringFieldEn() != null)
            fields.put("localized-string-field", Map.of("en", h.customLocalizedStringFieldEn()));
        if (h.customMoneyFieldCurrencyCode() != null && h.customMoneyFieldCentAmount() != null)
            fields.put("money-field", Map.of(
                    "currencyCode",  h.customMoneyFieldCurrencyCode(),
                    "centAmount",    Long.parseLong(h.customMoneyFieldCentAmount()),
                    "type",          h.customMoneyFieldType() != null ? h.customMoneyFieldType() : "centPrecision",
                    "fractionDigits", h.customMoneyFieldFractionDigits() != null
                            ? Integer.parseInt(h.customMoneyFieldFractionDigits()) : 2
            ));
        if (h.customEnumField() != null)
            fields.put("enum-field", h.customEnumField());
        if (h.customDateField() != null)
            fields.put("date-field", h.customDateField());
        if (h.customTimeField() != null)
            fields.put("time-field", h.customTimeField());
        if (h.customDateTimeField() != null)
            fields.put("date-time-field", h.customDateTimeField());

        if (fields.isEmpty()) return null;
        return new CustomFields(new ResourceIdentifier("type", h.customTypeKey()), fields);
    }

    // ── Assets ────────────────────────────────────────────────────────────────

    private List<AssetDraft> buildAssets(List<CategoryImportRecord> assetRows) {
        List<AssetDraft> result = new ArrayList<>();
        for (CategoryImportRecord r : assetRows) {
            if (r.assetKey() == null) continue;
            List<String> tags = r.assetTags() != null
                    ? Arrays.asList(r.assetTags().split(";"))
                    : null;
            result.add(new AssetDraft(
                    r.assetKey(),
                    localized("en", r.assetNameEn()),
                    r.assetSourcesUri() != null ? List.of(new AssetSource(r.assetSourcesUri())) : null,
                    localized("en", r.assetDescriptionEn()),
                    tags
            ));
        }
        return result.isEmpty() ? null : result;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private Map<String, String> localized(String locale, String value) {
        return value != null ? Map.of(locale, value) : null;
    }

    private Map<String, String> localizedMulti(String en, String deDe) {
        Map<String, String> map = new LinkedHashMap<>();
        if (en != null) map.put("en", en);
        if (deDe != null) map.put("de-DE", deDe);
        return map.isEmpty() ? null : map;
    }
}

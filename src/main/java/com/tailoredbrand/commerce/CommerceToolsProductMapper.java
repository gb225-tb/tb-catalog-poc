package com.tailoredbrand.commerce;

import com.tailoredbrand.commerce.CommerceToolsProductModels.*;
import com.tailoredbrand.model.ProductCsvRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps a {@link ProductCsvRecord} to the CommerceTools product API structures.
 *
 * <h3>Dual-product strategy (one CSV row → two CT products)</h3>
 *
 * <p><b>Tier-1 — "Product-Tier1" ({@code 9c405e7c-…})</b><br>
 * One product per {@code parentProductCode} (style level).
 * <ul>
 *   <li>Product key = {@code parentProductCode} (e.g. {@code "TMW_999"})</li>
 *   <li>masterVariant key = {@code parentProductCode-style}; SKU = {@code parentProductCode}</li>
 *   <li>All attributes are {@code level:"Product"} → placed in {@code ProductDraft.attributes}</li>
 *   <li>masterVariant carries no attributes and no price (style placeholder)</li>
 * </ul></p>
 *
 * <p><b>Tier-2 — "Product-Tier2" ({@code ce66dd7e-…})</b><br>
 * One product per {@code parentProductCode + productColorCode} (colour level).
 * <ul>
 *   <li>Product key = {@code parentProductCode-productColorCode} (e.g. {@code "TMW_999-15"})</li>
 *   <li>masterVariant key = {@code productKey-itemCode}; SKU = {@code itemCode}</li>
 *   <li>{@code level:"Product"} colour attrs → {@code ProductDraft.attributes}</li>
 *   <li>{@code level:"Variant"} size/flag attrs → {@code masterVariant.attributes} (CREATE)
 *       and variant attributes (ADD_VARIANT)</li>
 * </ul></p>
 *
 * <h3>Attribute type contract (derived from actual CT type definitions)</h3>
 * <pre>
 * Tier-1 required  text   : classCode, superGroup, group, superDivision, division,
 *                            divisionDescription, subDivision, webLongDescription,
 *                            fit, label, seasonCode, regularClassCode, taxTypeTaxware,
 *                            material, content, pattern, collectionName, categories, packageQty
 * Tier-1 required  number : webFlag, hazardousFlag, isBundle, isRental (0/1)
 * Tier-1 optional  text   : bigAndTallClassCode, wash, origin, careInstructions, jacketStyle,
 *                            jacketLining, jacketVent, pantStyle, pantFinish, accessoryStyle,
 *                            lapelStyle, pocketStyle, shirtCollarStyle, shirtCuffStyle,
 *                            shoeSoleMaterial, shoeStyle, shoeToeStyle, productFeatures,
 *                            additionalCopy, leverageClassCode, occasion, maxHemLength, monogramming
 * Tier-1 optional  number : width, creaset, hem
 *
 * Tier-2 product-level required  text   : productColorCode (as text!), colorDesc,
 *                                         colorBreakoutDesc, colorFamily, label
 * Tier-2 product-level optional  text   : webLongDesc, dateAvailableToSell
 * Tier-2 variant-level  required number : sizeCode, isPermanentMarkdown (0/1)
 * Tier-2 variant-level  required text   : primarySize (as text!), skulabel (← itemCode),
 *                                         taxTypeTaxware
 * Tier-2 variant-level  required bool   : isTemporaryMarkdown, isBigAndTall, noWarehouseStock,
 *                                         isClearance (false), isSale (false)
 * Tier-2 variant-level  optional text   : sizeDescription, sizeDimension, upc
 * Tier-2 variant-level  optional number : sizeSequence
 * </pre>
 */
public class CommerceToolsProductMapper {

    // ── Public: product key helpers (used by ApiClient) ─────────────────────

    /**
     * Returns the CT product key for a <b>Tier-1</b> record.
     * Equals {@code parentProductCode}.
     */
    public static String tier1ProductKey(ProductCsvRecord record) {
        return record.parentProductCode();
    }

    /**
     * Returns the CT product key for a <b>Tier-2</b> record.
     * Format: {@code parentProductCode-productColorCode} (e.g. {@code "TMW_999-15"}).
     */
    public static String tier2ProductKey(ProductCsvRecord record) {
        return record.parentProductCode() + "-" + record.itemCode();
    }

    // ── Public: draft builders ───────────────────────────────────────────────

    /**
     * Builds a {@link ProductDraft} for CT's {@code POST /products}.
     *
     * @param isTier2  {@code false} = Tier-1 (style product);
     *                 {@code true}  = Tier-2 (colour product)
     */
    public ProductDraft toProductDraft(ProductCsvRecord record, TypeRef typeRef, boolean isTier2) {
        String displayName = record.webLongDesc() != null && !record.webLongDesc().isBlank()
                ? record.webLongDesc()
                : record.parentProductCode();

        if (isTier2) {
            String tier2ProductKey = tier2ProductKey(record);
            ProductVariantDraft masterVariant = new ProductVariantDraft(
                    tier2ProductKey,
                    record.itemCode(),
                    buildPrices(record),
                    buildTier2VariantAttributes(record)
            );
            return new ProductDraft(
                    tier2ProductKey, typeRef,
                    new LocalizedString(displayName),
                    new LocalizedString(toSlug(tier2ProductKey)),
                    new LocalizedString(displayName),
                    buildTier2ProductAttributes(record),
                    masterVariant
            );
        }

        // Tier-1: style placeholder — masterVariant SKU = parentProductCode, no attrs, no price
        String productKey = tier1ProductKey(record);
        ProductVariantDraft masterVariant = new ProductVariantDraft(
                productKey + "-"+record.itemCode(),
                productKey,           // SKU = parentProductCode (style-level)
                new ArrayList<>(),    // no price on style-level variant
                null                  // all Tier-1 attrs are at product level
        );
        return new ProductDraft(
                productKey, typeRef,
                new LocalizedString(displayName),
                new LocalizedString(toSlug(productKey)),
                new LocalizedString(displayName),
                buildTier1ProductAttributes(record),
                masterVariant
        );
    }

    /**
     * Builds a {@link ProductVariantDraft} for an {@code addVariant} update action.
     *
     * <ul>
     *   <li>Tier-1: no attributes (all attrs live at the product level); no price.</li>
     *   <li>Tier-2: variant-level size/flag attributes; actual price.</li>
     * </ul>
     */
    public ProductVariantDraft toVariantDraft(ProductCsvRecord record, boolean isTier2) {
        if (isTier2) {
            String parentProductCode = record.parentProductCode();
            return new ProductVariantDraft(
                    parentProductCode + "-" + record.itemCode(), // Correct format
                    record.itemCode(),
                    buildPrices(record),
                    buildTier2VariantAttributes(record)
            );
        }
        // Tier-1 addVariant should not normally be reached — style product has one fixed variant
        return new ProductVariantDraft(
                tier1ProductKey(record) + record.itemCode(),
                tier1ProductKey(record),
                new ArrayList<>(),
                null
        );
    }

    // ── Prices ──────────────────────────────────────────────────────────────

    private List<PriceDraft> buildPrices(ProductCsvRecord record) {
        List<PriceDraft> prices = new ArrayList<>();
        if (record.msrp() != null && !record.msrp().isBlank()) {
            try {
                String priceKey = record.parentProductCode() + "-" + record.itemCode() + "-price-0";
                prices.add(PriceDraft.usd(priceKey, Double.parseDouble(record.msrp())));
            } catch (NumberFormatException ignored) {
            }
        }
        return prices;
    }

    // ── Tier-1 product-level attributes ─────────────────────────────────────

    private List<AttributeDraft> buildTier1ProductAttributes(ProductCsvRecord record) {
        List<AttributeDraft> attrs = new ArrayList<>();

        // REQUIRED text
        addRequiredText(attrs, "classCode",           record.leverageClassCode(),     "");
        addRequiredText(attrs, "superGroup",          record.superGroup(),            "");
        addRequiredText(attrs, "group",               record.group(),                 "");
        addRequiredText(attrs, "superDivision",       record.superDivision(),         "");
        addRequiredText(attrs, "division",            record.division(),              "");
        addRequiredText(attrs, "divisionDescription", record.divisionDescription(),   "");
        addRequiredText(attrs, "subDivision",         record.subDivision(),           "");
        addRequiredText(attrs, "webLongDescription",  record.webLongDesc(),           "");
        addRequiredText(attrs, "fit",                 record.fit(),                   "");
        addRequiredText(attrs, "label",               record.label(),                 "");
        addRequiredText(attrs, "seasonCode",          record.seasonCode(),            "");
        addRequiredText(attrs, "regularClassCode",    record.regClassCode(),          "");
        addRequiredText(attrs, "taxTypeTaxware",      record.taxTypeTaxware(),        "");
        addRequiredText(attrs, "material",            record.material(),              "");
        addRequiredText(attrs, "content",             record.content(),               "");
        addRequiredText(attrs, "pattern",             record.pattern(),               "");
        addRequiredText(attrs, "collectionName",      record.collectionName(),        "");
        addRequiredText(attrs, "categories",          record.categories(),            "");
        addRequiredText(attrs, "packageQty",          record.packageQty(),            "");

        // REQUIRED number
        addRequiredNumber (attrs, "webFlag",      record.webFlag(),       0L);
        addRequiredFlagInt(attrs, "hazardousFlag",record.hazardousFlag(), 0);
        addRequiredFlagInt(attrs, "isBundle",     record.isBundle(),      0);
        attrs.add(new AttributeDraft("isRental", 0));

        // OPTIONAL text
        addText(attrs, "bigAndTallClassCode", record.btClassCode());
        addText(attrs, "wash",               record.wash());
        addText(attrs, "origin",             record.origin());
        addText(attrs, "careInstructions",   record.careInstructions());
        addText(attrs, "jacketStyle",        record.jacketStyle());
        addText(attrs, "jacketLining",       record.jacketLining());
        addText(attrs, "jacketVent",         record.jacketVent());
        addText(attrs, "pantStyle",          record.pantStyle());
        addText(attrs, "pantFinish",         record.pantFinish());
        addText(attrs, "accessoryStyle",     record.accessoryStyle());
        addText(attrs, "lapelStyle",         record.lapelStyle());
        addText(attrs, "pocketStyle",        record.pocketStyle());
        addText(attrs, "shirtCollarStyle",   record.shirtCollarStyle());
        addText(attrs, "shirtCuffStyle",     record.shirtCuffStyle());
        addText(attrs, "shoeSoleMaterial",   record.shoeSoleMaterial());
        addText(attrs, "shoeStyle",          record.shoeStyle());
        addText(attrs, "shoeToeStyle",       record.shoeToeStyle());
        addText(attrs, "productFeatures",    record.productFeatures());
        addText(attrs, "additionalCopy",     record.additionalCopy());
        addText(attrs, "leverageClassCode",  record.leverageClassCode());
        addText(attrs, "occasion",           record.occasion());
        addText(attrs, "maxHemLength",       record.maxHemLength());
        addText(attrs, "monogramming",       record.monogramming());

        // OPTIONAL number
        addNumber(attrs, "width",   record.width());
        addNumber(attrs, "creaset", record.creaset());
        addNumber(attrs, "hem",     record.hem());

        return attrs;
    }

    // ── Tier-2 product-level attributes (colour group) ──────────────────────

    private List<AttributeDraft> buildTier2ProductAttributes(ProductCsvRecord record) {
        List<AttributeDraft> attrs = new ArrayList<>();
        // Set color attributes at product level (required by CommerceTools)
        addRequiredText(attrs, "productColorCode",  record.productColorCode(), "");
        addRequiredText(attrs, "colorDesc",         record.colorDesc(),        "");
        addRequiredText(attrs, "colorBreakoutDesc", record.colorBreakoutDesc(),"");
        addRequiredText(attrs, "colorFamily",       record.colorFamily(),      "");
        // Other product-level attributes
        addRequiredText(attrs, "label",             record.label(),            "");
        addText(attrs, "webLongDesc",         record.webLongDesc());
        addText(attrs, "dateAvailableToSell", record.dateAvailableToSell());
        return attrs;
    }

    // ── Tier-2 variant-level attributes (size / flags + color) ──────────────────────
    private List<AttributeDraft> buildTier2VariantAttributes(ProductCsvRecord record) {
        List<AttributeDraft> attrs = new ArrayList<>();
        // DO NOT add color attributes to variant level (CommerceTools expects them only at product level)
        // REQUIRED number
        addRequiredNumber (attrs, "sizeCode",          record.sizeCode(),            0L);
        addRequiredFlagInt(attrs, "isPermanentMarkdown",record.isPermanentMarkdown(), 0);

        // REQUIRED text (primarySize is text type in Tier-2, not number)
        addRequiredText(attrs, "primarySize",    record.primarySize(),  "");
        addRequiredText(attrs, "skulabel",       record.itemCode() != null ? record.itemCode() : "", "");
        addRequiredText(attrs, "taxTypeTaxware", record.taxTypeTaxware(), "");

        // REQUIRED boolean
        addRequiredFlagBool(attrs, "isTemporaryMarkdown", record.isTemporaryMarkDown(), false);
        addRequiredFlagBool(attrs, "isBigAndTall",        record.bigAndTallFlag(),      false);
        addRequiredFlagBool(attrs, "noWarehouseStock",    record.noWarehouseStock(),    false);
        attrs.add(new AttributeDraft("isClearance", false));
        attrs.add(new AttributeDraft("isSale",      false));

        // OPTIONAL text
        addText(attrs, "sizeDescription", record.sizeDescription());
        addText(attrs, "sizeDimension",   record.sizeDimension());
        addText(attrs, "upc",             record.upc());

        // OPTIONAL number
        addNumber(attrs, "sizeSequence", record.sizeSequence());

        return attrs;
    }

    // ── Helpers: optional ───────────────────────────────────────────────────

    private void addText(List<AttributeDraft> list, String name, String value) {
        if (value != null && !value.isBlank()) list.add(new AttributeDraft(name, value));
    }

    private void addNumber(List<AttributeDraft> list, String name, String value) {
        if (value == null || value.isBlank()) return;
        try {
            list.add(new AttributeDraft(name, Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            list.add(new AttributeDraft(name, value));
        }
    }

    // ── Helpers: required ────────────────────────────────────────────────────

    private void addRequiredText(List<AttributeDraft> list, String name, String value, String def) {
        list.add(new AttributeDraft(name, (value != null && !value.isBlank()) ? value : def));
    }

    private void addRequiredFlagInt(List<AttributeDraft> list, String name, String value, int def) {
        int v = (value != null && !value.isBlank()) ? ("1".equals(value) ? 1 : 0) : def;
        list.add(new AttributeDraft(name, v));
    }

    private void addRequiredFlagBool(List<AttributeDraft> list, String name, String value, boolean def) {
        boolean v = (value != null && !value.isBlank()) ? "1".equals(value) : def;
        list.add(new AttributeDraft(name, v));
    }

    private void addRequiredNumber(List<AttributeDraft> list, String name, String value, long def) {
        if (value == null || value.isBlank()) { list.add(new AttributeDraft(name, def)); return; }
        try {
            list.add(new AttributeDraft(name, Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            list.add(new AttributeDraft(name, def));
        }
    }

    // ── Slug ────────────────────────────────────────────────────────────────

    private String toSlug(String code) {
        if (code == null || code.isBlank()) return "unknown";
        return code.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /**
     * Groups records by parentProductCode and creates one ProductDraft per group.
     * Each variant will have color/size (tier 2) attributes only.
     */
    public List<ProductDraft> toProductDraftsGroupedByParent(List<ProductCsvRecord> records, TypeRef typeRef) {
        Map<String, List<ProductCsvRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(ProductCsvRecord::parentProductCode));
        List<ProductDraft> result = new ArrayList<>();
        for (Map.Entry<String, List<ProductCsvRecord>> entry : grouped.entrySet()) {
            String parentProductCode = entry.getKey();
            List<ProductCsvRecord> group = entry.getValue();
            ProductCsvRecord first = group.get(0);
            List<AttributeDraft> productAttrs = buildTier2ProductAttributes(first);
            List<ProductVariantDraft> variants = group.stream()
                    .map(r -> new ProductVariantDraft(
                            parentProductCode + "-" + r.itemCode(), // Correct format
                            r.itemCode(),
                            buildPrices(r),
                            buildTier2VariantAttributes(r)
                    ))
                    .collect(Collectors.toList());
            String displayName = first.webLongDesc() != null && !first.webLongDesc().isBlank()
                    ? first.webLongDesc()
                    : parentProductCode;
            // Only masterVariant in ProductDraft; additional variants will be added via update
            ProductDraft draft = new ProductDraft(
                    parentProductCode, typeRef,
                    new LocalizedString(displayName),
                    new LocalizedString(toSlug(parentProductCode)),
                    new LocalizedString(displayName),
                    productAttrs,
                    variants.get(0)
            );
            result.add(draft);
        }
        return result;
    }

    /**
     * Groups records by parentProductCode and productColorCode and creates one ProductDraft per color group.
     * Each product will have color attributes for that color, and all size variants for that color.
     */
    public List<ProductDraft> toProductDraftsGroupedByParentAndColor(List<ProductCsvRecord> records, TypeRef typeRef) {
        Map<String, List<ProductCsvRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(r -> r.parentProductCode() + "|" + r.productColorCode()));
        List<ProductDraft> result = new ArrayList<>();
        for (Map.Entry<String, List<ProductCsvRecord>> entry : grouped.entrySet()) {
            List<ProductCsvRecord> group = entry.getValue();
            ProductCsvRecord first = group.get(0);
            String productKey = first.parentProductCode() + "-" + first.productColorCode();
            List<AttributeDraft> productAttrs = buildTier2ProductAttributes(first);
            List<ProductVariantDraft> variants = group.stream()
                    .map(r -> new ProductVariantDraft(
                            productKey + "-" + r.itemCode(),
                            r.itemCode(),
                            buildPrices(r),
                            buildTier2VariantAttributes(r)
                    ))
                    .collect(Collectors.toList());
            String displayName = first.webLongDesc() != null && !first.webLongDesc().isBlank()
                    ? first.webLongDesc()
                    : productKey;
            ProductDraft draft = new ProductDraft(
                    productKey, typeRef,
                    new LocalizedString(displayName),
                    new LocalizedString(toSlug(productKey)),
                    new LocalizedString(displayName),
                    productAttrs,
                    variants.get(0)
            );
            result.add(draft);
        }
        return result;
    }
}

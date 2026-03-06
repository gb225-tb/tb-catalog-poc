package com.tailoredbrand.catalog.io;

import com.tailoredbrand.model.ProductCsvRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TupleTag;
import org.bson.Document;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Multi-output Beam DoFn that converts a flat {@link ProductCsvRecord} (the "universe row")
 * into three segregated MongoDB documents emitted on separate side outputs:
 *
 * <ul>
 *   <li>{@link #PRODUCT_TAG} – Style-level document keyed by {@code ParentProductCode}</li>
 *   <li>{@link #VARIANT_TAG} – Color-level document keyed by {@code ParentProductCode_ProductColorCode}</li>
 *   <li>{@link #SKU_TAG}     – Item/size-level document keyed by {@code ItemCode}</li>
 * </ul>
 *
 * <p>Each output is {@code KV<String, String>} where the key is the MongoDB {@code _id}
 * and the value is the serialised JSON string of the document.  Downstream,
 * a {@code GroupByKey} deduplicates multiple rows that share the same key
 * before the final {@link MongoUpsertFn} writes each document exactly once.
 *
 * <h3>Field segregation rationale</h3>
 * <ul>
 *   <li><b>Product</b> – attributes that are identical across all colours and sizes
 *       of the same style (description, fit, material, jacket/pant/shoe styling, etc.).</li>
 *   <li><b>Variant</b> – attributes that vary per colour but are the same across all
 *       sizes of that colour (colour description, colour family, MSRP, gender, etc.).</li>
 *   <li><b>SKU</b> – attributes that are unique to a single size/item
 *       (size code, size description, UPC, current cost, date available to sell, etc.).</li>
 * </ul>
 */
@Slf4j
public class CatalogExtractorFn extends DoFn<ProductCsvRecord, KV<String, String>> {

    public static final TupleTag<KV<String, String>> PRODUCT_TAG = new TupleTag<>() {};
    public static final TupleTag<KV<String, String>> VARIANT_TAG = new TupleTag<>() {};
    public static final TupleTag<KV<String, String>> SKU_TAG     = new TupleTag<>() {};

    @ProcessElement
    public void processElement(ProcessContext ctx, MultiOutputReceiver out) {
        ProductCsvRecord r = ctx.element();

        if (r.parentProductCode() == null || r.parentProductCode().isBlank()) {
            log.warn("[CATALOG] Skipping row with blank ParentProductCode | itemCode={}", r.itemCode());
            return;
        }

        String variantId = r.parentProductCode() + "_" + r.productColorCode();

        out.get(PRODUCT_TAG).output(KV.of(r.parentProductCode(), buildProductDoc(r).toJson()));
        out.get(VARIANT_TAG).output(KV.of(variantId,             buildVariantDoc(r, variantId).toJson()));
        out.get(SKU_TAG)    .output(KV.of(r.itemCode(),          buildSkuDoc(r, variantId).toJson()));
    }

    // ── Product document ─────────────────────────────────────────────────────
    // One document per ParentProductCode – style-level attributes.

    private Document buildProductDoc(ProductCsvRecord r) {
        Document doc = new Document();
        doc.put("_id",                 r.parentProductCode());
        doc.put("parentProductCode",   r.parentProductCode());

        // Taxonomy / classification
        doc.put("group",               r.group());
        doc.put("superGroup",          r.superGroup());
        doc.put("subDivision",         r.subDivision());
        doc.put("division",            r.division());
        doc.put("divisionDescription", r.divisionDescription());
        doc.put("superDivision",       r.superDivision());

        // Merchandising
        doc.put("regClassCode",        r.regClassCode());
        doc.put("btClassCode",         r.btClassCode());
        doc.put("leverageClassCode",   r.leverageClassCode());
        doc.put("seasonCode",          r.seasonCode());
        doc.put("label",               r.label());
        doc.put("fit",                 r.fit());
        doc.put("bigAndTallFlag",      flag(r.bigAndTallFlag()));
        doc.put("isBundle",            flag(r.isBundle()));
        doc.put("packageQty",          integer(r.packageQty()));
        doc.put("occasion",            r.occasion());

        // Web / content
        doc.put("webLongDescription",  r.webLongDesc());
        doc.put("webFlag",             flag(r.webFlag()));
        doc.put("productFeatures",     r.productFeatures());
        doc.put("additionalCopy",      r.additionalCopy());
        doc.put("collectionName",      r.collectionName());
        doc.put("careInstructions",    r.careInstructions());
        doc.put("categories",          splitPipe(r.categories()));
        doc.put("productAssociations", splitAngleBracket(r.productAssociation()));

        // Construction / materials
        doc.put("material",            r.material());
        doc.put("content",             r.content());
        doc.put("pattern",             r.pattern());
        doc.put("wash",                r.wash());
        doc.put("origin",              r.origin());

        // Style attributes – apparel-specific
        doc.put("width",               r.width());
        doc.put("jacketStyle",         r.jacketStyle());
        doc.put("jacketLining",        r.jacketLining());
        doc.put("jacketVent",          r.jacketVent());
        doc.put("pantStyle",           r.pantStyle());
        doc.put("pantFinish",          r.pantFinish());
        doc.put("accessoryStyle",      r.accessoryStyle());
        doc.put("lapelStyle",          r.lapelStyle());
        doc.put("pocketStyle",         r.pocketStyle());
        doc.put("shirtCollarStyle",    r.shirtCollarStyle());
        doc.put("shirtCuffStyle",      r.shirtCuffStyle());
        doc.put("shoeStyle",           r.shoeStyle());
        doc.put("shoeToeStyle",        r.shoeToeStyle());
        doc.put("shoeSoleMaterial",    r.shoeSoleMaterial());

        // Operational
        doc.put("hazardousFlag",       flag(r.hazardousFlag()));
        doc.put("noWarehouseStock",    flag(r.noWarehouseStock()));
        doc.put("taxTypeTaxware",      r.taxTypeTaxware());
        doc.put("creaset",             r.creaset());
        doc.put("hem",                 r.hem());
        doc.put("maxHemLength",        r.maxHemLength());
        doc.put("monogramming",        r.monogramming());

        // Timestamps
        doc.put("createdAt",           r.itemCreateDate());
        doc.put("updatedAt",           r.itemUpdateDate());
        doc.put("status",              "ACTIVE");

        return doc;
    }

    // ── Variant document ─────────────────────────────────────────────────────
    // One document per ParentProductCode + ProductColorCode – colour-level attributes.

    private Document buildVariantDoc(ProductCsvRecord r, String variantId) {
        Document doc = new Document();
        doc.put("_id",                        variantId);
        doc.put("variantId",                  variantId);
        doc.put("productId",                  r.parentProductCode());
        doc.put("colorCode",                  r.productColorCode());
        doc.put("colorDescription",           r.colorDesc());
        doc.put("colorBreakoutDescription",   r.colorBreakoutDesc());
        doc.put("colorFamily",                r.colorFamily());
        doc.put("msrp",                       decimal(r.msrp()));
        doc.put("isTemporaryMarkdown",        flag(r.isTemporaryMarkDown()));
        doc.put("isPermanentMarkdown",        flag(r.isPermanentMarkdown()));
        doc.put("specialSizes",               r.specialSizes());
        doc.put("gender",                     r.gender());
        doc.put("heelHeight",                 r.heelHeight());
        doc.put("length",                     r.length());
        doc.put("sleeveLength",               r.sleeveLength());
        doc.put("createdAt",                  r.itemCreateDate());
        doc.put("updatedAt",                  r.itemUpdateDate());
        doc.put("status",                     "ACTIVE");
        return doc;
    }

    // ── SKU document ─────────────────────────────────────────────────────────
    // One document per ItemCode – item/size-level attributes.

    private Document buildSkuDoc(ProductCsvRecord r, String variantId) {
        Document doc = new Document();
        doc.put("_id",                  r.itemCode());
        doc.put("itemCode",             r.itemCode());
        doc.put("productId",            r.parentProductCode());
        doc.put("variantId",            variantId);
        doc.put("sizeCode",             r.sizeCode());
        doc.put("sizeDescription",      r.sizeDescription());
        doc.put("primarySize",          r.primarySize());
        doc.put("sizeDimension",        r.sizeDimension());
        doc.put("sizeSequence",         integer(r.sizeSequence()));
        doc.put("msrp",                 decimal(r.msrp()));
        doc.put("currentCost",          decimal(r.currentCost()));
        doc.put("upc",                  r.upc());
        doc.put("dateAvailableToSell",  r.dateAvailableToSell());
        doc.put("createdAt",            r.itemCreateDate());
        doc.put("updatedAt",            r.itemUpdateDate());
        doc.put("status",               "ACTIVE");
        return doc;
    }

    // ── Type-conversion helpers ───────────────────────────────────────────────

    /** "1" / "true" → true, everything else → false. */
    private boolean flag(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    /** Parse to Integer; returns null on blank/invalid. */
    private Integer integer(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /** Parse to Double; returns null on blank/invalid. */
    private Double decimal(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /** Split a pipe-delimited string into a trimmed list, ignoring blanks. */
    private List<String> splitPipe(String v) {
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Split a {@code "<><><>VALUE<>"} delimited association string.
     * The CSV encodes multiple values separated by {@code "<>"}, often with blank segments.
     */
    private List<String> splitAngleBracket(String v) {
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split("<>"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}

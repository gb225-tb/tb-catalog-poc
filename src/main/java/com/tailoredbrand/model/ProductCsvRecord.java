package com.tailoredbrand.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.apache.beam.sdk.coders.DefaultCoder;
import org.apache.beam.sdk.coders.SerializableCoder;

import java.io.Serializable;

/**
 * Immutable record mapping every column of the pipe-delimited TBUniverseProducts CSV.
 *
 * Jackson serializes field names as UpperCamelCase via @JsonNaming; the three acronym
 * fields (MSRP, UPC, BTClassCode) are pinned with explicit @JsonProperty.
 */
@DefaultCoder(SerializableCoder.class)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public record ProductCsvRecord(
        String itemCode,
        String parentProductCode,
        String group,
        String superGroup,
        String subDivision,
        String division,
        String divisionDescription,
        String superDivision,
        String itemCreateDate,
        String itemUpdateDate,
        String webLongDesc,
        String webFlag,
        String sizeCode,
        String sizeDescription,
        String primarySize,
        String sizeDimension,
        String sizeSequence,
        String productColorCode,
        String colorDesc,
        String colorBreakoutDesc,
        String colorFamily,
        @JsonProperty("MSRP") String msrp,
        String currentCost,
        String isTemporaryMarkDown,
        String isPermanentMarkdown,
        String fit,
        String label,
        String seasonCode,
        String bigAndTallFlag,
        @JsonProperty("BTClassCode") String btClassCode,
        String regClassCode,
        String hazardousFlag,
        String noWarehouseStock,
        String taxTypeTaxware,
        String heelHeight,
        String length,
        String material,
        String content,
        String pattern,
        String wash,
        String origin,
        String specialSizes,
        String gender,
        String collectionName,
        String careInstructions,
        String categories,
        String width,
        String jacketStyle,
        String jacketLining,
        String jacketVent,
        String pantStyle,
        String pantFinish,
        String accessoryStyle,
        String lapelStyle,
        String pocketStyle,
        String shirtCollarStyle,
        String shirtCuffStyle,
        String sleeveLength,
        String shoeStyle,
        String shoeToeStyle,
        String shoeSoleMaterial,
        String productFeatures,
        String additionalCopy,
        String productAssociation,
        String dateAvailableToSell,
        @JsonProperty("UPC") String upc,
        String leverageClassCode,
        String occasion,
        String creaset,
        String hem,
        String maxHemLength,
        String monogramming,
        String packageQty,
        String isBundle
) implements Serializable {
}

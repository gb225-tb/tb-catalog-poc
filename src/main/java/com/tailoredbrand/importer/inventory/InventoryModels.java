package com.tailoredbrand.importer.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** CT Inventory API models used by the file-upload import pipeline. */
public class InventoryModels {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InventoryEntryDraft(
            String key,
            String sku,
            Long quantityOnStock,
            Integer restockableInDays,
            ResourceIdentifier supplyChannel,
            CustomFields custom
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceIdentifier(String typeId, String key) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomFields(ResourceIdentifier type, Map<String, Object> fields) {}

    /** Minimal projection to verify inventory-entry existence. */
    public record InventoryExistsResponse(String id, String key, Long version) {}
}

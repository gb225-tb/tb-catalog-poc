package com.tailoredbrand.importer.discount;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** CT Discount Codes API models used by the file-upload import pipeline. */
public class DiscountCodeModels {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiscountCodeDraft(
            String key,
            Map<String, String> name,
            String code,
            List<ResourceIdentifier> cartDiscounts,
            Boolean isActive,
            String validFrom,
            String validUntil,
            Integer maxApplications,
            Integer maxApplicationsPerCustomer,
            List<String> groups,
            CustomFields custom
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceIdentifier(String typeId, String key) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomFields(ResourceIdentifier type, Map<String, Object> fields) {}

    /** Minimal projection to verify discount-code existence. */
    public record DiscountCodeExistsResponse(String id, String key, Long version) {}
}

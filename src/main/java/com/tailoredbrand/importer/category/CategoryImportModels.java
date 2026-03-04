package com.tailoredbrand.importer.category;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** CT Categories API models used by the file-upload import pipeline. */
public class CategoryImportModels {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CategoryDraft(
            String key,
            Map<String, String> name,
            Map<String, String> slug,
            Map<String, String> description,
            ResourceIdentifier parent,
            String orderHint,
            String externalId,
            Map<String, String> metaTitle,
            Map<String, String> metaDescription,
            Map<String, String> metaKeywords,
            CustomFields custom,
            List<AssetDraft> assets
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceIdentifier(String typeId, String key) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomFields(ResourceIdentifier type, Map<String, Object> fields) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssetDraft(
            String key,
            Map<String, String> name,
            List<AssetSource> sources,
            Map<String, String> description,
            List<String> tags
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssetSource(String uri) {}

    /** Minimal projection used only to verify category existence. */
    public record CategoryExistsResponse(String id, String key, Long version) {}
}

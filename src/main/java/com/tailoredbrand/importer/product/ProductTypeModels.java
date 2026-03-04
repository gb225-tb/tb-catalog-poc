package com.tailoredbrand.importer.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Java records for the CommerceTools Product Types API.
 *
 * <pre>
 *  GET  /{project}/product-types/key={key}  →  ProductTypeResponse
 *  POST /{project}/product-types            →  ProductTypeDraft (body)
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductTypeModels {

    private ProductTypeModels() {}

    // ── Attribute type descriptors ────────────────────────────────────────────

    /** A localizable enum value (key + locale-labeled display text). */
    public record LEnumValue(String key, Map<String, String> label) {}

    /** Type descriptor — set {@code name} to "text", "boolean", "lenum", etc. */
    public record AttributeType(
            String name,
            List<LEnumValue> values  // only for lenum / enum types
    ) {
        public static AttributeType text() {
            return new AttributeType("text", null);
        }

        public static AttributeType bool() {
            return new AttributeType("boolean", null);
        }

        public static AttributeType lenum(List<LEnumValue> values) {
            return new AttributeType("lenum", values);
        }
    }

    // ── Attribute definition draft ────────────────────────────────────────────

    /**
     * One attribute definition inside a {@link ProductTypeDraft}.
     * {@code attributeConstraint} values: {@code "None"}, {@code "Unique"},
     * {@code "CombinationUnique"}, {@code "SameForAll"}.
     */
    public record AttributeDefinitionDraft(
            AttributeType type,
            String name,
            Map<String, String> label,
            boolean isRequired,
            String attributeConstraint,
            boolean isSearchable,
            String inputHint    // "SingleLine" | "MultiLine"
    ) {}

    // ── Product type draft ────────────────────────────────────────────────────

    public record ProductTypeDraft(
            String key,
            String name,
            String description,
            List<AttributeDefinitionDraft> attributes
    ) {}

    // ── GET response ──────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductTypeResponse(String id, String key) {}
}

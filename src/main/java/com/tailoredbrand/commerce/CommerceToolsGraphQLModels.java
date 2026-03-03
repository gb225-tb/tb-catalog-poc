package com.tailoredbrand.commerce;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Typed Java records that model the CommerceTools GraphQL API response for the
 * {@code product(key: "…")} query.
 *
 * <h3>Response envelope</h3>
 * <pre>
 * {
 *   "data": { "product": { … } },  // present on success
 *   "errors": [ { "message": "…" } ]  // present on query / auth errors
 * }
 * </pre>
 */
public class CommerceToolsGraphQLModels {

    // ── Envelope ─────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GraphQLResponse<T>(T data, List<GraphQLError> errors) {

        /** Returns {@code true} when the response contains one or more GraphQL errors. */
        public boolean hasErrors() {
            return errors != null && !errors.isEmpty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GraphQLError(String message, List<Object> locations, List<String> path) {}

    /** Top-level {@code data} field for the product query. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductQueryData(CTProduct product) {}

    // ── Product ───────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTProduct(
            String id,
            String key,
            Long version,
            String createdAt,
            CTProductType productType,
            CTMasterData masterData
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTProductType(String id) {}

    // ── Master data ───────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTMasterData(
            boolean published,
            boolean hasStagedChanges,
            CTProductData current,
            CTStagedData staged
    ) {}

    /**
     * The {@code current} projection — fully expanded including variants and attributes.
     *
     * <p>Note: {@code name}, {@code description}, and {@code slug} are returned as plain
     * {@code String} values because the GraphQL query requests them with an explicit
     * {@code locale: "en-US"} argument, which causes CT to resolve them to a single string.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTProductData(
            String name,
            String description,
            String slug,
            List<CTCategory> categories,
            List<CTRawAttribute> attributesRaw,
            CTVariant masterVariant,
            List<CTVariant> variants
    ) {}

    /** The {@code staged} projection — name / description / slug + raw product attributes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTStagedData(
            String name,
            String description,
            String slug,
            List<CTRawAttribute> attributesRaw
    ) {}

    // ── Supporting types ──────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTCategory(String id) {}

    /**
     * Raw attribute returned by the GraphQL {@code attributesRaw} field.
     *
     * <p>{@code value} is typed as {@link Object} because CT attribute values can be
     * primitives, strings, booleans, numbers, or even nested objects (e.g. enum types,
     * money, reference).  Jackson will deserialize it as the closest native Java type:
     * {@code String}, {@code Integer}/{@code Long}, {@code Boolean}, {@code List}, or
     * {@code Map}.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTRawAttribute(String name, Object value) {}

    /** A product variant (used for both {@code masterVariant} and {@code variants}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTVariant(
            Integer id,
            String sku,
            List<CTPrice> prices,
            List<CTImage> images,
            List<CTRawAttribute> attributesRaw
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTPrice(@JsonProperty("value") CTMoney value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTMoney(
            @JsonProperty("centAmount")   Long centAmount,
            @JsonProperty("currencyCode") String currencyCode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CTImage(String url) {}
}

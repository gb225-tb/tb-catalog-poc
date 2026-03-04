package com.tailoredbrand.importer.businessunit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Java records that mirror the CommerceTools Business Units API shapes.
 *
 * <pre>
 *  GET  /{project}/business-units/key={key}   →  BuExistsResponse
 *  POST /{project}/business-units             →  BusinessUnitDraft (body)
 * </pre>
 *
 * @see <a href="https://docs.commercetools.com/api/projects/business-units">
 *      CT Business Units API</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BusinessUnitModels {

    private BusinessUnitModels() {}

    // ── Shared reference ──────────────────────────────────────────────────────

    public record TypeRef(@JsonProperty("typeId") String typeId, String key) {}

    // ── Address ───────────────────────────────────────────────────────────────

    public record CustomFields(@JsonProperty("type") TypeRef type,
                               @JsonProperty("fields") Map<String, Object> fields) {}

    public record AddressDraft(
            String key,
            String country,
            String company,
            String streetName,
            String streetNumber,
            String building,
            @JsonProperty("pOBox")   String pOBox,
            String apartment,
            String city,
            String postalCode,
            String region,
            String state,
            String additionalStreetInfo,
            CustomFields custom
    ) {}

    // ── Associate ─────────────────────────────────────────────────────────────

    public record AssociateRoleAssignment(
            @JsonProperty("associateRole") TypeRef associateRole,
            String inheritance
    ) {}

    public record AssociateDraft(
            List<AssociateRoleAssignment> associateRoleAssignments,
            TypeRef customer
    ) {}

    // ── Store reference ───────────────────────────────────────────────────────

    // ── Business Unit draft ───────────────────────────────────────────────────

    /**
     * Payload for {@code POST /{project}/business-units}.
     *
     * <p>The {@code unitType} discriminator determines which CT resource subtype
     * is created:
     * <ul>
     *   <li>{@code "Company"} — top-level BU; {@code parentUnit} must be null.</li>
     *   <li>{@code "Division"} — child BU; {@code parentUnit} is required.</li>
     * </ul></p>
     */
    public record BusinessUnitDraft(
            String key,
            String name,
            String status,
            String unitType,
            TypeRef parentUnit,
            String storeMode,
            List<TypeRef> stores,
            String associateMode,
            String approvalRuleMode,
            String contactEmail,
            List<AddressDraft> addresses,
            List<Integer> shippingAddresses,
            Integer defaultShippingAddress,
            List<Integer> billingAddresses,
            Integer defaultBillingAddress,
            List<AssociateDraft> associates,
            CustomFields custom
    ) {}

    // ── GET response (minimal) ────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuExistsResponse(String id, String key, Long version) {}
}

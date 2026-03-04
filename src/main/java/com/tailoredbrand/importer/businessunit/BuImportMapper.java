package com.tailoredbrand.importer.businessunit;

import com.tailoredbrand.importer.businessunit.BusinessUnitModels.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Maps a {@link BuImportGroup} to a {@link BusinessUnitDraft} ready for the CT
 * Business Units API.
 *
 * <h3>Mapping rules</h3>
 * <ul>
 *   <li>BU-level fields (key, name, unitType, stores…) come from the header row.</li>
 *   <li>Associates: the header row's associate columns provide the first entry;
 *       each {@code associateRows} entry provides additional ones.</li>
 *   <li>Addresses: the header row's address columns provide the first entry;
 *       each {@code addressRows} entry provides additional ones.</li>
 *   <li>{@code shippingAddresses} / {@code billingAddresses} are resolved by
 *       matching the semicolon-separated address keys in the header row against
 *       the index of each {@link AddressDraft} in the final addresses list.</li>
 *   <li>For Divisions, {@code parentUnit} is set from {@code parentUnit.key} and
 *       stores are omitted (inherited from parent when {@code storeMode=FromParent}).</li>
 * </ul>
 */
@Component
@Slf4j
public class BuImportMapper {

    public BusinessUnitDraft toBusinessUnitDraft(BuImportGroup group) {
        BuImportRecord header = group.header();

        List<AddressDraft> addresses = buildAddresses(header, group.addressRows());
        Map<String, Integer> addressKeyIndex = buildAddressKeyIndex(addresses);

        return new BusinessUnitDraft(
                header.key(),
                header.name(),
                header.status(),
                header.unitType(),
                buildParentUnit(header),
                header.storeMode(),
                buildStores(header),
                header.associateMode(),
                header.approvalRuleMode(),
                header.contactEmail(),
                addresses.isEmpty() ? null : addresses,
                resolveAddressIndices(header.shippingAddressKeys(), addressKeyIndex),
                resolveAddressIndex(header.defaultShippingAddressKey(), addressKeyIndex),
                resolveAddressIndices(header.billingAddressKeys(), addressKeyIndex),
                resolveAddressIndex(header.defaultBillingAddressKey(), addressKeyIndex),
                buildAssociates(header, group.associateRows()),
                buildCustomFields(header.customTypeKey(), header.customEmployeeCount())
        );
    }

    // ── Parent unit (Division only) ───────────────────────────────────────────

    private TypeRef buildParentUnit(BuImportRecord header) {
        if (header.parentUnitKey() == null || header.parentUnitKey().isBlank()) return null;
        return new TypeRef("business-unit", header.parentUnitKey());
    }

    // ── Stores ────────────────────────────────────────────────────────────────

    private List<TypeRef> buildStores(BuImportRecord header) {
        if (header.stores() == null || header.stores().isBlank()) return null;
        List<TypeRef> stores = Arrays.stream(header.stores().split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(key -> new TypeRef("store", key))
                .toList();
        return stores.isEmpty() ? null : stores;
    }

    // ── Addresses ─────────────────────────────────────────────────────────────

    private List<AddressDraft> buildAddresses(BuImportRecord header,
                                               List<BuImportRecord> extraRows) {
        List<AddressDraft> list = new ArrayList<>();
        if (header.hasAddress()) {
            list.add(toAddressDraft(header));
        }
        for (BuImportRecord row : extraRows) {
            if (row.hasAddress()) {
                list.add(toAddressDraft(row));
            }
        }
        return list;
    }

    private AddressDraft toAddressDraft(BuImportRecord row) {
        return new AddressDraft(
                row.addressKey(),
                row.addressCountry(),
                row.addressCompany(),
                row.addressStreetName(),
                row.addressStreetNumber(),
                row.addressBuilding(),
                row.addressPOBox(),
                row.addressApartment(),
                row.addressCity(),
                row.addressPostalCode(),
                row.addressRegion(),
                row.addressState(),
                row.addressAdditionalStreetInfo(),
                buildAddressCustomFields(row.addressCustomTypeKey(), row.addressCustomTimeZone())
        );
    }

    private CustomFields buildAddressCustomFields(String typeKey, String timeZone) {
        if (typeKey == null || timeZone == null) return null;
        return new CustomFields(
                new TypeRef("type", typeKey),
                Map.of("time-zone", timeZone)
        );
    }

    // ── Address index resolution ──────────────────────────────────────────────

    private Map<String, Integer> buildAddressKeyIndex(List<AddressDraft> addresses) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < addresses.size(); i++) {
            if (addresses.get(i).key() != null) {
                index.put(addresses.get(i).key(), i);
            }
        }
        return index;
    }

    private List<Integer> resolveAddressIndices(String semicolonKeys,
                                                 Map<String, Integer> index) {
        if (semicolonKeys == null || semicolonKeys.isBlank()) return null;
        List<Integer> indices = Arrays.stream(semicolonKeys.split(";"))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .map(key -> {
                    Integer idx = index.get(key);
                    if (idx == null) log.warn("[BU MAPPER] Address key '{}' not found in address list", key);
                    return idx;
                })
                .filter(Objects::nonNull)
                .toList();
        return indices.isEmpty() ? null : indices;
    }

    private Integer resolveAddressIndex(String key, Map<String, Integer> index) {
        if (key == null || key.isBlank()) return null;
        Integer idx = index.get(key);
        if (idx == null) log.warn("[BU MAPPER] Default address key '{}' not found in address list", key);
        return idx;
    }

    // ── Associates ────────────────────────────────────────────────────────────

    private List<AssociateDraft> buildAssociates(BuImportRecord header,
                                                   List<BuImportRecord> extraRows) {
        List<AssociateDraft> list = new ArrayList<>();
        if (header.hasAssociate()) {
            list.add(toAssociateDraft(header));
        }
        for (BuImportRecord row : extraRows) {
            list.add(toAssociateDraft(row));
        }
        return list.isEmpty() ? null : list;
    }

    private AssociateDraft toAssociateDraft(BuImportRecord row) {
        AssociateRoleAssignment assignment = new AssociateRoleAssignment(
                new TypeRef("associate-role", row.associateRoleKey()),
                row.associateRoleInheritance()
        );
        TypeRef customer = new TypeRef("customer", row.associateCustomerKey());
        return new AssociateDraft(List.of(assignment), customer);
    }

    // ── BU custom fields ──────────────────────────────────────────────────────

    private CustomFields buildCustomFields(String typeKey, String employeeCount) {
        if (typeKey == null) return null;
        Map<String, Object> fields = new LinkedHashMap<>();
        if (employeeCount != null && !employeeCount.isBlank()) {
            try {
                fields.put("employee-count", Integer.parseInt(employeeCount.trim()));
            } catch (NumberFormatException e) {
                fields.put("employee-count", employeeCount);
            }
        }
        return new CustomFields(new TypeRef("type", typeKey), fields.isEmpty() ? null : fields);
    }
}

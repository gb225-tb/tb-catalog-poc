package com.tailoredbrand.io;

import com.tailoredbrand.commerce.*;
import com.tailoredbrand.commerce.CommerceToolsProductModels.ProductDraft;
import com.tailoredbrand.commerce.CommerceToolsProductModels.TypeRef;
import com.tailoredbrand.model.ProductApiResult;
import com.tailoredbrand.model.ProductCsvRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.transforms.DoFn;

import java.util.List;

/**
 * Beam DoFn to upsert a grouped product (one per parentProductCode, with all variants).
 */
@Slf4j
public class CommerceProductGroupedUpsertFn extends DoFn<List<ProductCsvRecord>, ProductApiResult> {
    private final CommerceToolsSettings settings;
    private transient CommerceToolsTokenService tokenService;
    private transient CommerceToolsApiClient apiClient;
    private transient CommerceToolsProductMapper mapper;
    private transient TypeRef secondaryTypeRef;

    public CommerceProductGroupedUpsertFn(CommerceToolsSettings settings) {
        this.settings = settings;
    }

    @Setup
    public void setup() {
        tokenService = new CommerceToolsTokenService(settings);
        CommerceToolsApiClient preflight = new CommerceToolsApiClient(settings, tokenService, null);
        // Use secondary type for grouped product with variants
        secondaryTypeRef = null;
        String secondaryKey = settings.getSecondaryProductTypeKey();
        if (secondaryKey != null && !secondaryKey.isBlank()) {
            secondaryTypeRef = preflight.validateAndResolveProductTypeByKey(secondaryKey);
        } else {
            throw new IllegalStateException("secondaryProductTypeKey must be set in pipeline.yaml for grouped product with variants");
        }
        mapper = new CommerceToolsProductMapper();
        apiClient = new CommerceToolsApiClient(settings, tokenService, mapper);
        apiClient.initTypeRefs(null, secondaryTypeRef);
    }

    @ProcessElement
    public void processElement(ProcessContext ctx) {
        List<ProductCsvRecord> group = ctx.element();
        if (group == null || group.isEmpty()) return;
        // Group by parentProductCode and productColorCode
        List<ProductDraft> drafts = mapper.toProductDraftsGroupedByParentAndColor(group, secondaryTypeRef);
        for (ProductDraft draft : drafts) {
            // Upsert the product (create or update with masterVariant only)
            ProductApiResult result = apiClient.upsertGroupedProduct(draft);
            ctx.output(result);
            // Find all records for this product (color group)
            String[] keyParts = draft.key().split("-");
            String parentProductCode = keyParts[0];
            String productColorCode = keyParts.length > 1 ? keyParts[1] : "";
            List<ProductCsvRecord> colorGroup = group.stream()
                .filter(r -> r.parentProductCode().equals(parentProductCode) && r.productColorCode().equals(productColorCode))
                .toList();
            // Add additional variants via update actions
            if (colorGroup.size() > 1) {
                for (int i = 1; i < colorGroup.size(); i++) {
                    ProductCsvRecord rec = colorGroup.get(i);
                    CommerceToolsProductModels.ProductVariantDraft variant = mapper.toVariantDraft(rec, true);
                    ProductApiResult variantResult = apiClient.addVariantToProduct(draft.key(), variant);
                    ctx.output(variantResult);
                }
            }
        }
    }
}
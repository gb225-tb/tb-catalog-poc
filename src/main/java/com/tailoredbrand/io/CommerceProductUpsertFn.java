package com.tailoredbrand.io;

import com.tailoredbrand.commerce.CommerceToolsApiClient;
import com.tailoredbrand.commerce.CommerceToolsProductMapper;
import com.tailoredbrand.commerce.CommerceToolsSettings;
import com.tailoredbrand.commerce.CommerceToolsTokenService;
import com.tailoredbrand.model.ProductApiResult;
import com.tailoredbrand.model.ProductCsvRecord;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.transforms.DoFn;

/**
 * Apache Beam {@link DoFn} that upserts one {@link ProductCsvRecord} into
 * CommerceTools via the Products API.
 *
 * <h3>Product-type resolution (pre-flight)</h3>
 * <table border="1">
 *   <tr><th>pipeline.yaml setting</th><th>Behaviour</th></tr>
 *   <tr>
 *     <td>{@code productTypeKey: "my-type"}</td>
 *     <td>Validated against CT. Fails fast with an actionable message if not found,
 *         listing all available keys.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code productTypeKey:} (blank / absent)</td>
 *     <td>Auto-detects: uses the sole type if exactly one exists; fails fast with
 *         the full list if the project has multiple types.</td>
 *   </tr>
 * </table>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@code @Setup} — acquires an OAuth token, runs pre-flight product-type
 *       resolution, then builds the API client with the resolved key.</li>
 *   <li>{@code @ProcessElement} — delegates to
 *       {@link CommerceToolsApiClient#upsert}: GET → create-or-addVariant
 *       with exponential-backoff retries.</li>
 * </ol>
 *
 * <p>Only {@link CommerceToolsSettings} is serialized by Beam; all transient
 * resources (WebClient, token cache) are recreated lazily after worker restarts.</p>
 */
@Slf4j
public class CommerceProductUpsertFn extends DoFn<ProductCsvRecord, ProductApiResult> {

    private final CommerceToolsSettings settings;

    // Transient — rebuilt in @Setup / lazily inside each client
    private transient CommerceToolsTokenService tokenService;
    private transient CommerceToolsApiClient    apiClient;

    public CommerceProductUpsertFn(CommerceToolsSettings settings) {
        this.settings = settings;
    }

    @Setup
    public void setup() {
        log.info("[SETUP] Initializing CommerceProductUpsertFn | project={} | primaryProductTypeKey='{}'",
                settings.getProjectKey(),
                settings.getProductTypeKey() != null && !settings.getProductTypeKey().isBlank()
                        ? settings.getProductTypeKey()
                        : "(auto-detect)");

        tokenService = new CommerceToolsTokenService(settings);

        // Use a temporary client (no mapper needed) for product-type pre-flight resolution.
        CommerceToolsApiClient preflight = new CommerceToolsApiClient(
                settings, tokenService, null
        );

        // Resolve primary product type
        com.tailoredbrand.commerce.CommerceToolsProductModels.TypeRef primaryTypeRef =
                preflight.validateAndResolveProductType();

        // Resolve secondary product type (when configured)
        com.tailoredbrand.commerce.CommerceToolsProductModels.TypeRef secondaryTypeRef = null;
        String secondaryKey = settings.getSecondaryProductTypeKey();
        if (secondaryKey != null && !secondaryKey.isBlank()) {
            log.info("[SETUP] Resolving secondary productTypeKey='{}'", secondaryKey);
            secondaryTypeRef = preflight.validateAndResolveProductTypeByKey(secondaryKey);
            log.info("[SETUP] ✓ Secondary product type resolved → {}", secondaryTypeRef);
        }

        // Build the real client; inject both TypeRefs so it can route per record
        apiClient = new CommerceToolsApiClient(
                settings,
                tokenService,
                new CommerceToolsProductMapper()
        );
        apiClient.initTypeRefs(primaryTypeRef, secondaryTypeRef);

        log.info("[SETUP] ✓ Ready | project={} | primaryType={} | secondaryType={}",
                settings.getProjectKey(), primaryTypeRef, secondaryTypeRef);
    }

    @ProcessElement
    public void processElement(ProcessContext ctx) {
        // One CSV row → up to two CT upserts (Tier-1 style product + Tier-2 colour product).
        // Each result is emitted independently so the ResultLoggerFn sees both outcomes.
        apiClient.upsertAll(ctx.element()).forEach(ctx::output);
    }
}

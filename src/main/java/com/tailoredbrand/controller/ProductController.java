package com.tailoredbrand.controller;

import com.tailoredbrand.commerce.CommerceToolsGraphQLModels.CTProduct;
import com.tailoredbrand.commerce.CommerceToolsGraphQLService;
import com.tailoredbrand.commerce.CommerceToolsGraphQLService.CommerceToolsGraphQLException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for product catalogue operations.
 *
 * <p>All reads are served by the <b>CommerceTools GraphQL API</b>
 * ({@code POST /{projectKey}/graphql}) so the response shape matches
 * exactly what the CT GraphQL endpoint returns.</p>
 *
 * <h3>Equivalent cURL</h3>
 * <pre>
 * curl --location --request GET 'http://localhost:8080/api/products/TMW_17FM'
 * </pre>
 *
 * <p>Internally this translates to:</p>
 * <pre>
 * POST https://api.us-central1.gcp.commercetools.com/data-import/graphql
 * Authorization: Bearer &lt;token&gt;
 * {"query":"query { product(key: \"TMW_17FM\") { … } }","variables":{}}
 * </pre>
 */
@RestController
@RequestMapping("/api/products")
@Tag(name = "Products", description = "Catalogue product look-up via CommerceTools GraphQL")
@Slf4j
public class ProductController {

    private final CommerceToolsGraphQLService graphQLService;

    public ProductController(CommerceToolsGraphQLService graphQLService) {
        this.graphQLService = graphQLService;
    }

    // ── GET /api/products/{key} ───────────────────────────────────────────────

    @Operation(
            summary = "Get product by key",
            description = """
                    Fetches a fully-expanded product from CommerceTools by its unique key.

                    The response includes:
                    - product metadata (id, key, version, createdAt, productType)
                    - masterData.current — name, description, slug, product-level attributes,
                      masterVariant (with prices, images, variant attributes), and all variants
                    - masterData.staged  — staged name, description, slug and attributes

                    The CT GraphQL endpoint is called internally; the caller only needs
                    this service's Bearer token (or no auth if the endpoint is public).
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Product found and returned"),
            @ApiResponse(responseCode = "404", description = "No product exists with the given key",
                    content = @Content(examples = @ExampleObject(value = ""))),
            @ApiResponse(responseCode = "502", description = "CommerceTools GraphQL returned an error",
                    content = @Content(examples = @ExampleObject(
                            value = "{\"error\":\"CT GraphQL error: ...\",\"status\":502}")))
    })
    @GetMapping("/{key}")
    public ResponseEntity<?> getProductByKey(
            @Parameter(description = "CommerceTools product key", example = "TMW_17FM")
            @PathVariable String key) {

        log.info("[PRODUCT API] GET /api/products/{}", key);

        CTProduct product = graphQLService.getProduct(key);

        if (product == null) {
            log.info("[PRODUCT API] 404 – product not found | key={}", key);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(product);
    }

    // ── POST /api/products/graphql ────────────────────────────────────────────

    /**
     * Raw GraphQL pass-through.
     *
     * <p>Accepts the same JSON payload you would send directly to CT's GraphQL endpoint
     * and proxies it transparently.  The Bearer token is injected automatically —
     * no CT credentials are needed by the caller.</p>
     *
     * <h3>Example cURL</h3>
     * <pre>
     * curl --location 'http://localhost:8080/tb-catalog-poc/api/products/graphql' \
     *   --header 'Content-Type: application/json' \
     *   --data-raw '{
     *     "query": "query { product(key: \"TMW_17FM\") { id key version masterData { current { name(locale: \"en-US\") } } } }",
     *     "variables": {}
     *   }'
     * </pre>
     */
    @Operation(
            summary = "Execute a raw GraphQL query against CommerceTools",
            description = "Proxies the supplied GraphQL payload directly to CT. " +
                          "The Authorization token is injected automatically."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CT responded (may include a GraphQL errors array)"),
            @ApiResponse(responseCode = "400", description = "Request body is missing or query field is blank"),
            @ApiResponse(responseCode = "502", description = "CT returned an HTTP-level error")
    })
    @PostMapping("/graphql")
    public ResponseEntity<Map<String, Object>> executeGraphQL(
            @Valid @RequestBody GraphQLRequest request) {

        log.info("[PRODUCT API] POST /api/products/graphql");
        Map<String, Object> result = graphQLService.execute(request.query(), request.variables());
        return ResponseEntity.ok(result);
    }

    /**
     * Request body for the pass-through GraphQL endpoint.
     *
     * @param query     GraphQL query / mutation string (required)
     * @param variables optional variables map; may be {@code null} or omitted
     */
    public record GraphQLRequest(
            @NotBlank(message = "query must not be blank") String query,
            Map<String, Object> variables
    ) {}

    // ── Exception handler (scoped to this controller) ─────────────────────────

    @ExceptionHandler(CommerceToolsGraphQLException.class)
    public ResponseEntity<ErrorBody> handleGraphQLException(CommerceToolsGraphQLException ex) {
        log.error("[PRODUCT API] CT GraphQL error | status={} msg={}", ex.getHttpStatus(), ex.getMessage());
        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(new ErrorBody(ex.getMessage(), ex.getHttpStatus()));
    }

    public record ErrorBody(String error, int status) {}
}

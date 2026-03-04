package com.tailoredbrand.controller;

import com.tailoredbrand.importer.ImportSummary;
import com.tailoredbrand.importer.businessunit.BusinessUnitImportService;
import com.tailoredbrand.importer.category.CategoryImportService;
import com.tailoredbrand.importer.discount.DiscountCodeImportService;
import com.tailoredbrand.importer.inventory.InventoryImportService;
import com.tailoredbrand.importer.product.ProductImportService;
import com.tailoredbrand.importer.productdelete.ProductDeleteImportService;
import com.tailoredbrand.importer.productimage.ProductImageImportService;
import com.tailoredbrand.importer.productprice.ProductPriceImportService;
import com.tailoredbrand.importer.productpricetier.ProductPriceTierImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * REST controller for CSV file-upload import pipelines.
 *
 * <table>
 *   <tr><th>Endpoint</th><th>CSV format</th><th>CT resource</th></tr>
 *   <tr><td>POST /api/import/products</td><td>multi-row product CSV</td><td>Products</td></tr>
 *   <tr><td>POST /api/import/business-units</td><td>multi-row BU CSV</td><td>Business Units</td></tr>
 *   <tr><td>POST /api/import/categories</td><td>multi-row category CSV</td><td>Categories</td></tr>
 *   <tr><td>POST /api/import/discount-codes</td><td>single-row discount-code CSV</td><td>Discount Codes</td></tr>
 *   <tr><td>POST /api/import/inventory</td><td>single-row inventory CSV</td><td>Inventory Entries</td></tr>
 *   <tr><td>POST /api/import/products/delete</td><td>key-only CSV</td><td>Products (DELETE)</td></tr>
 *   <tr><td>POST /api/import/products/images</td><td>per-image-row CSV</td><td>Product Variant Images</td></tr>
 *   <tr><td>POST /api/import/products/prices</td><td>per-price-row CSV</td><td>Product Variant Prices</td></tr>
 *   <tr><td>POST /api/import/products/price-tiers</td><td>per-tier-row CSV</td><td>Product Price Tiers</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api/import")
@Tag(name = "Import", description = "CSV file-upload import pipelines for CommerceTools resources")
@Slf4j
public class ImportController {

    private final ProductImportService       productImportService;
    private final BusinessUnitImportService  buImportService;
    private final CategoryImportService      categoryImportService;
    private final DiscountCodeImportService  discountCodeImportService;
    private final InventoryImportService     inventoryImportService;
    private final ProductDeleteImportService productDeleteImportService;
    private final ProductImageImportService  productImageImportService;
    private final ProductPriceImportService  productPriceImportService;
    private final ProductPriceTierImportService productPriceTierImportService;

    public ImportController(ProductImportService productImportService,
                            BusinessUnitImportService buImportService,
                            CategoryImportService categoryImportService,
                            DiscountCodeImportService discountCodeImportService,
                            InventoryImportService inventoryImportService,
                            ProductDeleteImportService productDeleteImportService,
                            ProductImageImportService productImageImportService,
                            ProductPriceImportService productPriceImportService,
                            ProductPriceTierImportService productPriceTierImportService) {
        this.productImportService         = productImportService;
        this.buImportService              = buImportService;
        this.categoryImportService        = categoryImportService;
        this.discountCodeImportService    = discountCodeImportService;
        this.inventoryImportService       = inventoryImportService;
        this.productDeleteImportService   = productDeleteImportService;
        this.productImageImportService    = productImageImportService;
        this.productPriceImportService    = productPriceImportService;
        this.productPriceTierImportService = productPriceTierImportService;
    }

    // ── POST /api/import/products ─────────────────────────────────────────────

    @Operation(
            summary = "Import products from a CT-format CSV",
            description = "Accepts a CT Import API-format product CSV (multi-row per product). " +
                          "Each product group is upserted to the CT Products API."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Import completed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"total":1,"succeeded":1,"failed":0,
                                     "results":[{"key":"geometric-pillow-case","operation":"create","success":true,"statusCode":201}]}"""))),
            @ApiResponse(responseCode = "400", description = "No file or empty file"),
            @ApiResponse(responseCode = "500", description = "Failed to read/parse CSV")
    })
    @PostMapping(value = "/products", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importProducts(
            @Parameter(description = "CT-format product CSV", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("[IMPORT API] POST /api/import/products | file={} size={}", file.getOriginalFilename(), file.getSize());
        if (file.isEmpty()) return ResponseEntity.badRequest().body("Uploaded file is empty");

        try {
            ImportSummary summary = ImportSummary.of(productImportService.importProducts(file.getInputStream()));
            log.info("[IMPORT API] Products done | total={} ok={} fail={}", summary.total(), summary.succeeded(), summary.failed());
            return ResponseEntity.ok(summary);
        } catch (IOException ex) {
            log.error("[IMPORT API] Failed to read product CSV", ex);
            return ResponseEntity.internalServerError().body("Failed to read uploaded file: " + ex.getMessage());
        }
    }

    // ── POST /api/import/business-units ──────────────────────────────────────

    @Operation(
            summary = "Import business units from a CT B2B CSV",
            description = "Accepts a CT Business Unit import CSV (multi-row per BU). " +
                          "Companies are processed before Divisions."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Import completed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"total":3,"succeeded":3,"failed":0,
                                     "results":[{"key":"lifttech-solutions-ltd","operation":"create","success":true,"statusCode":201}]}"""))),
            @ApiResponse(responseCode = "400", description = "No file or empty file"),
            @ApiResponse(responseCode = "500", description = "Failed to read/parse CSV")
    })
    @PostMapping(value = "/business-units", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importBusinessUnits(
            @Parameter(description = "CT B2B Business Unit CSV", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("[IMPORT API] POST /api/import/business-units | file={} size={}", file.getOriginalFilename(), file.getSize());
        if (file.isEmpty()) return ResponseEntity.badRequest().body("Uploaded file is empty");

        try {
            ImportSummary summary = ImportSummary.of(buImportService.importBusinessUnits(file.getInputStream()));
            log.info("[IMPORT API] BU done | total={} ok={} fail={}", summary.total(), summary.succeeded(), summary.failed());
            return ResponseEntity.ok(summary);
        } catch (IOException ex) {
            log.error("[IMPORT API] Failed to read BU CSV", ex);
            return ResponseEntity.internalServerError().body("Failed to read uploaded file: " + ex.getMessage());
        }
    }

    // ── POST /api/import/categories ───────────────────────────────────────────

    @Operation(
            summary = "Import categories from a CT category CSV",
            description = "Accepts a CT category import CSV (multi-row per category with asset rows). " +
                          "Top-level categories are created before child categories. " +
                          "Missing custom types are auto-created in the pre-flight step."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Import completed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"total":2,"succeeded":2,"failed":0,
                                     "results":[
                                       {"key":"category-key-1","operation":"create","success":true,"statusCode":201},
                                       {"key":"category-key-2","operation":"create","success":true,"statusCode":201}
                                     ]}"""))),
            @ApiResponse(responseCode = "400", description = "No file or empty file"),
            @ApiResponse(responseCode = "500", description = "Failed to read/parse CSV")
    })
    @PostMapping(value = "/categories", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importCategories(
            @Parameter(description = "CT category CSV", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("[IMPORT API] POST /api/import/categories | file={} size={}", file.getOriginalFilename(), file.getSize());
        if (file.isEmpty()) return ResponseEntity.badRequest().body("Uploaded file is empty");

        try {
            ImportSummary summary = ImportSummary.of(categoryImportService.importCategories(file.getInputStream()));
            log.info("[IMPORT API] Categories done | total={} ok={} fail={}", summary.total(), summary.succeeded(), summary.failed());
            return ResponseEntity.ok(summary);
        } catch (IOException ex) {
            log.error("[IMPORT API] Failed to read category CSV", ex);
            return ResponseEntity.internalServerError().body("Failed to read uploaded file: " + ex.getMessage());
        }
    }

    // ── POST /api/import/discount-codes ──────────────────────────────────────

    @Operation(
            summary = "Import discount codes from a CT discount-code CSV",
            description = "Accepts a single-row-per-code CSV. " +
                          "Referenced cart discounts are auto-created as inactive placeholders if missing. " +
                          "Missing custom types are also auto-created."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Import completed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"total":1,"succeeded":1,"failed":0,
                                     "results":[{"key":"bogo","operation":"create","success":true,"statusCode":201}]}"""))),
            @ApiResponse(responseCode = "400", description = "No file or empty file"),
            @ApiResponse(responseCode = "500", description = "Failed to read/parse CSV")
    })
    @PostMapping(value = "/discount-codes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importDiscountCodes(
            @Parameter(description = "CT discount-code CSV", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("[IMPORT API] POST /api/import/discount-codes | file={} size={}", file.getOriginalFilename(), file.getSize());
        if (file.isEmpty()) return ResponseEntity.badRequest().body("Uploaded file is empty");

        try {
            ImportSummary summary = ImportSummary.of(discountCodeImportService.importDiscountCodes(file.getInputStream()));
            log.info("[IMPORT API] Discount codes done | total={} ok={} fail={}", summary.total(), summary.succeeded(), summary.failed());
            return ResponseEntity.ok(summary);
        } catch (IOException ex) {
            log.error("[IMPORT API] Failed to read discount-code CSV", ex);
            return ResponseEntity.internalServerError().body("Failed to read uploaded file: " + ex.getMessage());
        }
    }

    // ── POST /api/import/inventory ────────────────────────────────────────────

    @Operation(
            summary = "Import inventory entries from a CT inventory CSV",
            description = "Accepts a single-row-per-entry CSV. " +
                          "Supply channels are auto-created with the InventorySupply role if missing. " +
                          "Missing custom types are also auto-created."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Import completed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"total":1,"succeeded":1,"failed":0,
                                     "results":[{"key":"inventory-key","operation":"create","success":true,"statusCode":201}]}"""))),
            @ApiResponse(responseCode = "400", description = "No file or empty file"),
            @ApiResponse(responseCode = "500", description = "Failed to read/parse CSV")
    })
    @PostMapping(value = "/inventory", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importInventory(
            @Parameter(description = "CT inventory entry CSV", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("[IMPORT API] POST /api/import/inventory | file={} size={}", file.getOriginalFilename(), file.getSize());
        if (file.isEmpty()) return ResponseEntity.badRequest().body("Uploaded file is empty");

        try {
            ImportSummary summary = ImportSummary.of(inventoryImportService.importInventory(file.getInputStream()));
            log.info("[IMPORT API] Inventory done | total={} ok={} fail={}", summary.total(), summary.succeeded(), summary.failed());
            return ResponseEntity.ok(summary);
        } catch (IOException ex) {
            log.error("[IMPORT API] Failed to read inventory CSV", ex);
            return ResponseEntity.internalServerError().body("Failed to read uploaded file: " + ex.getMessage());
        }
    }

    // ── POST /api/import/products/delete ─────────────────────────────────────

    @Operation(
            summary = "Delete products listed in a key-only CSV",
            description = "Accepts a single-column CSV containing product keys (one per line). " +
                          "Each product is unpublished (if published) then permanently deleted. " +
                          "Products that do not exist are silently skipped."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Delete run completed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"total":3,"succeeded":3,"failed":0,
                                     "results":[
                                       {"key":"first-product-to-delete","operation":"delete","success":true,"statusCode":200},
                                       {"key":"second-product-to-delete","operation":"skip","success":true,"statusCode":200},
                                       {"key":"third-product-to-delete","operation":"delete","success":true,"statusCode":200}
                                     ]}"""))),
            @ApiResponse(responseCode = "400", description = "No file or empty file"),
            @ApiResponse(responseCode = "500", description = "Failed to read/parse CSV")
    })
    @PostMapping(value = "/products/delete", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> deleteProducts(
            @Parameter(description = "Key-only product CSV (header: key)", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("[IMPORT API] POST /api/import/products/delete | file={} size={}", file.getOriginalFilename(), file.getSize());
        if (file.isEmpty()) return ResponseEntity.badRequest().body("Uploaded file is empty");

        try {
            ImportSummary summary = ImportSummary.of(productDeleteImportService.deleteProducts(file.getInputStream()));
            log.info("[IMPORT API] Product delete done | total={} ok={} fail={}", summary.total(), summary.succeeded(), summary.failed());
            return ResponseEntity.ok(summary);
        } catch (IOException ex) {
            log.error("[IMPORT API] Failed to read product-delete CSV", ex);
            return ResponseEntity.internalServerError().body("Failed to read uploaded file: " + ex.getMessage());
        }
    }

    // ── POST /api/import/products/images ─────────────────────────────────────

    @Operation(
            summary     = "Add images to existing product variants",
            description = "Accepts a CSV with one image per row (url, label, dimensions). "
                        + "Images are added to the staged version of each product via "
                        + "'addExternalImage' update actions. Duplicate URLs are silently skipped."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Import completed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"total":1,"succeeded":1,"failed":0,
                                     "results":[{"key":"geometric-pillow-case","operation":"update","success":true,"statusCode":200}]}"""))),
            @ApiResponse(responseCode = "400", description = "No file or empty file"),
            @ApiResponse(responseCode = "500", description = "Failed to read/parse CSV")
    })
    @PostMapping(value = "/products/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importProductImages(
            @Parameter(description = "CT product image CSV", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("[IMPORT API] POST /api/import/products/images | file={} size={}", file.getOriginalFilename(), file.getSize());
        if (file.isEmpty()) return ResponseEntity.badRequest().body("Uploaded file is empty");

        try {
            ImportSummary summary = ImportSummary.of(productImageImportService.importImages(file.getInputStream()));
            log.info("[IMPORT API] Product images done | total={} ok={} fail={}", summary.total(), summary.succeeded(), summary.failed());
            return ResponseEntity.ok(summary);
        } catch (IOException ex) {
            log.error("[IMPORT API] Failed to read product-image CSV", ex);
            return ResponseEntity.internalServerError().body("Failed to read uploaded file: " + ex.getMessage());
        }
    }

    // ── POST /api/import/products/prices ─────────────────────────────────────

    @Operation(
            summary     = "Add or update prices on existing product variants",
            description = "Accepts a CSV with one price per row (currency, amount, country, channel, "
                        + "validity dates, custom fields). Uses 'addPrice' for new prices and "
                        + "'changePrice' for prices whose key already exists on the variant. "
                        + "Missing channels and custom types are auto-created in a pre-flight step."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Import completed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"total":1,"succeeded":1,"failed":0,
                                     "results":[{"key":"geometric-pillow-case","operation":"update","success":true,"statusCode":200}]}"""))),
            @ApiResponse(responseCode = "400", description = "No file or empty file"),
            @ApiResponse(responseCode = "500", description = "Failed to read/parse CSV")
    })
    @PostMapping(value = "/products/prices", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importProductPrices(
            @Parameter(description = "CT product price CSV", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("[IMPORT API] POST /api/import/products/prices | file={} size={}", file.getOriginalFilename(), file.getSize());
        if (file.isEmpty()) return ResponseEntity.badRequest().body("Uploaded file is empty");

        try {
            ImportSummary summary = ImportSummary.of(productPriceImportService.importPrices(file.getInputStream()));
            log.info("[IMPORT API] Product prices done | total={} ok={} fail={}", summary.total(), summary.succeeded(), summary.failed());
            return ResponseEntity.ok(summary);
        } catch (IOException ex) {
            log.error("[IMPORT API] Failed to read product-price CSV", ex);
            return ResponseEntity.internalServerError().body("Failed to read uploaded file: " + ex.getMessage());
        }
    }

    // ── POST /api/import/products/price-tiers ────────────────────────────────

    @Operation(
            summary     = "Apply volume-tier pricing to existing product prices",
            description = "Accepts a CSV with one tier row per quantity break (multiple rows per price "
                        + "key, continuation rows have a blank price key). Locates the existing price "
                        + "on the staged product by key, then updates it via 'changePrice' with the "
                        + "full tiers array. The price must already exist; use the prices import "
                        + "endpoint first if needed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Import completed",
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {"total":1,"succeeded":1,"failed":0,
                                     "results":[{"key":"price-key-3","operation":"update","success":true,"statusCode":200}]}"""))),
            @ApiResponse(responseCode = "400", description = "No file or empty file"),
            @ApiResponse(responseCode = "500", description = "Failed to read/parse CSV")
    })
    @PostMapping(value = "/products/price-tiers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importProductPriceTiers(
            @Parameter(description = "CT product price-tiers CSV", required = true)
            @RequestParam("file") MultipartFile file) {

        log.info("[IMPORT API] POST /api/import/products/price-tiers | file={} size={}", file.getOriginalFilename(), file.getSize());
        if (file.isEmpty()) return ResponseEntity.badRequest().body("Uploaded file is empty");

        try {
            ImportSummary summary = ImportSummary.of(productPriceTierImportService.importPriceTiers(file.getInputStream()));
            log.info("[IMPORT API] Product price tiers done | total={} ok={} fail={}", summary.total(), summary.succeeded(), summary.failed());
            return ResponseEntity.ok(summary);
        } catch (IOException ex) {
            log.error("[IMPORT API] Failed to read product-price-tiers CSV", ex);
            return ResponseEntity.internalServerError().body("Failed to read uploaded file: " + ex.getMessage());
        }
    }
}

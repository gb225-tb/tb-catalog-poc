# CSV to Commerce Tools Data Mapping Guide

## Overview

The `TBUniverseProducts.csv` file is processed through an Apache Beam pipeline and mapped to Commerce Tools using a **dual-product strategy**. Each CSV row generates up to **two products** in Commerce Tools (Tier-1 and Tier-2), with specific attributes placed at different levels.

---

## Pipeline Flow

```
CSV File (TBUniverseProducts.csv)
    ↓
TextIO.read() - Reads pipe-delimited file
    ↓
CsvParserFn - Parses rows into ProductCsvRecord objects
    ├─→ Valid rows → VALID output
    └─→ Bad rows → DEAD_LETTER output
    ↓
Reshuffle - Distributes work across workers
    ↓
CommerceProductUpsertFn - Maps to Commerce Tools & executes API calls
    ├─→ Tier-1 Product (Style Level)
    └─→ Tier-2 Product (Color Level)
    ↓
ResultLoggerFn - Logs success/failure results
```

---

## CSV Structure

**File:** `src/main/resources/Data/TBUniverseProducts.csv`
**Format:** Pipe-delimited (|)
**Column Count:** 74 columns
**Key Identifier Columns:**
- `ItemCode` (Col 0) - Unique item variant
- `ParentProductCode` (Col 1) - Style/parent grouping
- `ProductColorCode` (Col 17) - Color grouping

**Sample Row:**
```
TMW17FM36101|TMW_17FM|SV2BSEAS|...|Navy|...
```

---

## Parsing & Mapping

### Step 1: CSV Parsing
**Class:** `ProductCsvParser` / `CsvParserFn`

- Splits each line on the pipe delimiter (|)
- Validates exactly 74 columns
- Creates `ProductCsvRecord` object with all 74 fields
- Dead-letters malformed rows (logged with reason)

**ProductCsvRecord fields include:**
```java
itemCode, parentProductCode, group, superGroup, subDivision, division, 
divisionDescription, superDivision, itemCreateDate, itemUpdateDate, 
webLongDesc, webFlag, sizeCode, sizeDescription, primarySize, sizeDimension, 
sizeSequence, productColorCode, colorDesc, colorBreakoutDesc, colorFamily, 
msrp, currentCost, isTemporaryMarkDown, isPermanentMarkdown, fit, label, 
seasonCode, bigAndTallFlag, btClassCode, regClassCode, hazardousFlag, 
noWarehouseStock, taxTypeTaxware, heelHeight, length, material, content, 
pattern, wash, origin, specialSizes, gender, collectionName, 
careInstructions, categories, width, jacketStyle, jacketLining, jacketVent, 
pantStyle, pantFinish, accessoryStyle, lapelStyle, pocketStyle, 
shirtCollarStyle, shirtCuffStyle, sleeveLength, shoeStyle, shoeToeStyle, 
shoeSoleMaterial, productFeatures, additionalCopy, productAssociation, 
dateAvailableToSell, upc, leverageClassCode, occasion, creaset, hem, 
maxHemLength, monogramming, packageQty, isBundle
```

### Step 2: Mapping to Commerce Tools
**Class:** `CommerceToolsProductMapper`

Each CSV row generates **up to 2 products**:

#### **Tier-1: Style Product (Parent Level)**
**Purpose:** One product per unique `parentProductCode`

| Property | Value | Source |
|----------|-------|--------|
| **Product Key** | `parentProductCode` | CSV Col 1 (e.g., "TMW_17FM") |
| **Display Name** | `webLongDesc` OR `parentProductCode` | CSV Col 10 or fallback |
| **Slug** | Lowercase, kebab-cased key | Derived from product key |
| **Master Variant Key** | `{parentProductCode}-style` | Computed |
| **Master Variant SKU** | `parentProductCode` | CSV Col 1 |
| **Price** | None (style placeholder) | N/A |
| **Type** | Primary Product Type | From pipeline.yaml config |

**Tier-1 Product-Level Attributes (all REQUIRED):**

| Attribute Name | CSV Source | Type | Example Value |
|----------------|-----------|------|----------------|
| classCode | leverageClassCode | text | "17FN" |
| superGroup | superGroup | text | "SVSEASSPCOAT" |
| group | group | text | "SV2BSEAS" |
| superDivision | superDivision | text | "MEN" |
| division | division | text | "10" |
| divisionDescription | divisionDescription | text | "SPORT COATS" |
| subDivision | subDivision | text | "SEASONALSPORTCOATS" |
| webLongDescription | webLongDesc | text | "WILKE MODERN 2BTN NOTCH..." |
| fit | fit | text | "MODERN FIT" |
| label | label | text | "WILKE-RODRIGUEZ" |
| seasonCode | seasonCode | text | "RPT" |
| regularClassCode | regClassCode | text | "17FM" |
| taxTypeTaxware | taxTypeTaxware | text | "2038356" |
| material | material | text | "POLYESTER BLEND" |
| content | content | text | "72% POLYESTER, 25% RAYON..." |
| pattern | pattern | text | "SOLID" |
| collectionName | collectionName | text | "ONLINE ONLY" |
| categories | categories | text | "SPORT COATS" |
| packageQty | packageQty | text | "0" |
| webFlag | webFlag | number | 1 (0/1) |
| hazardousFlag | hazardousFlag | number | 0 (0/1) |
| isBundle | isBundle | number | 0 (0/1) |
| isRental | (hardcoded) | number | 0 |

**Tier-1 Product-Level Attributes (OPTIONAL):**

| Attribute Name | CSV Source | Type |
|----------------|-----------|------|
| bigAndTallClassCode | btClassCode | text |
| wash | wash | text |
| origin | origin | text |
| careInstructions | careInstructions | text |
| jacketStyle | jacketStyle | text |
| jacketLining | jacketLining | text |
| jacketVent | jacketVent | text |
| pantStyle | pantStyle | text |
| pantFinish | pantFinish | text |
| accessoryStyle | accessoryStyle | text |
| lapelStyle | lapelStyle | text |
| pocketStyle | pocketStyle | text |
| shirtCollarStyle | shirtCollarStyle | text |
| shirtCuffStyle | shirtCuffStyle | text |
| shoeSoleMaterial | shoeSoleMaterial | text |
| shoeStyle | shoeStyle | text |
| shoeToeStyle | shoeToeStyle | text |
| productFeatures | productFeatures | text |
| additionalCopy | additionalCopy | text |
| leverageClassCode | leverageClassCode | text |
| occasion | occasion | text |
| maxHemLength | maxHemLength | text |
| monogramming | monogramming | text |
| width | width | number |
| creaset | creaset | number |
| hem | hem | number |

---

#### **Tier-2: Color Product (Color Level)**
**Purpose:** One product per unique `parentProductCode + productColorCode` combination

| Property | Value | Source |
|----------|-------|--------|
| **Product Key** | `{parentProductCode}-{productColorCode}` | CSV Cols 1 + 17 (e.g., "TMW_17FM-01") |
| **Display Name** | `webLongDesc` OR product key | CSV Col 10 or fallback |
| **Slug** | Lowercase, kebab-cased key | Derived from product key |
| **Master Variant Key** | `{productKey}-{itemCode}` | Computed (e.g., "TMW_17FM-01-TMW17FM36101") |
| **Master Variant SKU** | `itemCode` | CSV Col 0 |
| **Price** | MSRP (if available) | CSV Col 21, converted to USD CentPrecision |
| **Type** | Primary or Secondary Product Type | From settings (division-based routing) |

**Tier-2 Product-Level Attributes (COLOR GROUP - REQUIRED):**

| Attribute Name | CSV Source | Type | Example Value |
|----------------|-----------|------|----------------|
| productColorCode | productColorCode | text | "01" |
| colorDesc | colorDesc | text | "NAVY SOLID" |
| colorBreakoutDesc | colorBreakoutDesc | text | "NAVY" |
| colorFamily | colorFamily | text | "BLUE" |
| label | label | text | "WILKE-RODRIGUEZ" |

**Tier-2 Product-Level Attributes (OPTIONAL):**

| Attribute Name | CSV Source | Type |
|----------------|-----------|------|
| webLongDesc | webLongDesc | text |
| dateAvailableToSell | dateAvailableToSell | text |

**Tier-2 Variant-Level Attributes (SIZE/FLAGS - REQUIRED):**

| Attribute Name | CSV Source | Type | Example Value |
|----------------|-----------|------|----------------|
| sizeCode | sizeCode | number | 361 |
| isPermanentMarkdown | isPermanentMarkdown | number | 0 (0/1) |
| primarySize | primarySize | text | "36" |
| skulabel | itemCode | text | "TMW17FM36101" |
| taxTypeTaxware | taxTypeTaxware | text | "2038356" |
| isTemporaryMarkdown | isTemporaryMarkDown | boolean | false |
| isBigAndTall | bigAndTallFlag | boolean | false |
| noWarehouseStock | noWarehouseStock | boolean | false |
| isClearance | (hardcoded) | boolean | false |
| isSale | (hardcoded) | boolean | false |

**Tier-2 Variant-Level Attributes (OPTIONAL):**

| Attribute Name | CSV Source | Type |
|----------------|-----------|------|
| sizeDescription | sizeDescription | text |
| sizeDimension | sizeDimension | text |
| upc | upc | text |
| sizeSequence | sizeSequence | number |

---

## Commerce Tools API Calls

### Per-Row Upsert Logic
**Class:** `CommerceToolsApiClient`

For **each CSV row**, the system makes:

#### **Call 1: Check Tier-1 Product**
```
GET /{project}/products/key={parentProductCode}
```

**Response Handling:**
- **404 Not Found** → POST create Tier-1 ProductDraft
  - Includes product metadata
  - Master variant with key=`{parentProductCode}-style`, SKU=`parentProductCode`
  - No variant-level attributes (all attributes at product level)
  
- **200 OK** → Product exists → No action (style product is created only once)

#### **Call 2: Check Tier-2 Product**
```
GET /{project}/products/key={parentProductCode}-{productColorCode}
```

**Response Handling:**
- **404 Not Found** → POST create Tier-2 ProductDraft
  - Master variant with key=`{productKey}-{itemCode}`, SKU=`{itemCode}`
  - Includes price, product-level color attributes, variant-level size attributes
  
- **200 OK (variant SKU exists)** → Skip (variant already exists)
  
- **200 OK (variant SKU not found)** → POST addVariant action
  - Adds new size variant to existing color product
  - Includes variant-level attributes and price

**Retry Logic:**
- Exponential backoff on 4xx/5xx errors (configurable max retries)
- Default: 3 retries, 500ms base delay

---

## Type Resolution & Routing

**Configuration:** `pipeline.yaml` → `commerce` section

```yaml
commerce:
  productTypeKey: "my-product-type"  # Single type for all
  # OR for dual-type routing:
  productTypeKey: "type-1"
  secondaryProductTypeKey: "type-2"
  primaryProductTypeDivisions:
    - "10"  # division="10" → use primary type
    - "20"
  # all other divisions → use secondary type
```

**At Pipeline Startup:**
1. Validates configured product type(s) exist
2. Fails fast with list of available types if not found
3. Routes each record based on division value (if dual-type configured)

---

## Data Transformation Rules

### Type Conversions

| CSV Format | CT Type | Conversion | Example |
|-----------|---------|-----------|---------|
| Free text | Text | As-is (trimmed) | "NAVY SOLID" |
| "1" / "0" | Boolean | "1" → true, else false | "1" → true |
| "1" / "0" | Number (0/1) | "1" → 1, else 0 | "1" → 1 |
| Numeric string | Number | Parsed as Long | "361" → 361 |
| Price string | Money (USD) | Parsed as Double, stored in CentPrecision | "46.38" → 4638 (cents) |

### Fallback Values

- **Empty/null attributes:** 
  - Optional fields: omitted from payload
  - Required fields: empty string ("") or 0
  
- **Display Name:**
  - Uses `webLongDesc` if available
  - Falls back to product key if empty

- **Slug:**
  - Lowercase, kebab-cased product key
  - Removes non-alphanumeric characters
  - Trims leading/trailing hyphens

---

## Error Handling

**CSV Parsing Errors:**
- Bad format, wrong column count
- Logged to dead-letter side output
- Pipeline continues (fault-tolerant)

**Commerce Tools API Errors:**
- Retried with exponential backoff
- Failed API calls logged with operation, status, and error message
- Each result (success/failure) emitted independently

**Validation Errors:**
- Product type not found → Fails immediately with list of available types
- Missing required config → Fails with actionable error message

---

## Example: Single CSV Row → Multiple CT Operations

### Input CSV Row:
```
TMW17FM36101|TMW_17FM|SV2BSEAS|SVSEASSPCOAT|SEASONALSPORTCOATS|10|SPORT COATS|MEN|...|WILKE MODERN 2BTN NOTCH SV 3/8 LINED SPORT COAT|1|361|36 Short|36|SHORT|1700|01|NAVY SOLID|NAVY|BLUE||46.38|...
```

### Generated Operations:

**1. Tier-1 Product Creation (if not exists):**
```json
POST /products
{
  "productType": {"id": "type-id-1"},
  "key": "TMW_17FM",
  "name": {"en": "WILKE MODERN 2BTN NOTCH SV 3/8 LINED SPORT COAT"},
  "slug": {"en": "tmw_17fm"},
  "masterVariant": {
    "key": "TMW_17FM-style",
    "sku": "TMW_17FM"
  },
  "attributes": [
    {"name": "classCode", "value": "17FN"},
    {"name": "division", "value": "10"},
    {"name": "divisionDescription", "value": "SPORT COATS"},
    {"name": "fit", "value": "MODERN FIT"},
    {"name": "label", "value": "WILKE-RODRIGUEZ"},
    ...
  ]
}
```

**2. Tier-2 Product Creation (if not exists):**
```json
POST /products
{
  "productType": {"id": "type-id-1"},
  "key": "TMW_17FM-01",
  "name": {"en": "WILKE MODERN 2BTN NOTCH SV 3/8 LINED SPORT COAT"},
  "slug": {"en": "tmw_17fm-01"},
  "masterVariant": {
    "key": "TMW_17FM-01-TMW17FM36101",
    "sku": "TMW17FM36101",
    "prices": [
      {
        "currencyCode": "USD",
        "centAmount": 4638
      }
    ],
    "attributes": [
      {"name": "primarySize", "value": "36"},
      {"name": "sizeCode", "value": 361},
      {"name": "skulabel", "value": "TMW17FM36101"},
      {"name": "colorDesc", "value": "NAVY SOLID"},
      {"name": "productColorCode", "value": "01"},
      ...
    ]
  },
  "attributes": [
    {"name": "colorDesc", "value": "NAVY SOLID"},
    {"name": "productColorCode", "value": "01"},
    {"name": "colorFamily", "value": "BLUE"},
    ...
  ]
}
```

**3. Subsequent CSV rows (same color, different size):**
```json
POST /actions
{
  "action": "addVariant",
  "sku": "TMW17FM36102",  // New size variant
  "key": "TMW_17FM-01-TMW17FM36102",
  "prices": [...],
  "attributes": [
    {"name": "primarySize", "value": "36"},
    {"name": "sizeCode", "value": 362},
    ...
  ]
}
```

---

## Tools & Technologies

| Component | Technology | Role |
|-----------|-----------|------|
| **Pipeline Framework** | Apache Beam | Batch processing, distributed execution |
| **CSV Parsing** | Custom `ProductCsvParser` | Pipe-delimited format, validation |
| **HTTP Client** | Spring WebClient (Reactor) | REST API calls to Commerce Tools |
| **Serialization** | Jackson ObjectMapper | JSON conversion for API payloads |
| **Authentication** | OAuth 2.0 Token Service | Managed API access tokens |
| **Logging** | SLF4J (Logback) | Structured logging for debugging |

---

## Configuration Files

**Pipeline Configuration:** `src/main/resources/config/pipeline.yaml`
```yaml
csv:
  inputFile: "path/to/TBUniverseProducts.csv"

commerce:
  authUrl: "https://auth.commercetools.com"
  clientCredentials: "client-id:client-secret"
  scope: "manage_products:my-project"
  apiUrl: "https://api.commercetools.com"
  projectKey: "my-project"
  productTypeKey: "product-type-key"
  secondaryProductTypeKey: "secondary-type-key"  # optional
  primaryProductTypeDivisions:
    - "10"
    - "20"
  connectTimeoutMs: 5000
  readTimeoutMs: 15000
  maxRetries: 3
  backoffMs: 500
```

---

## Summary

- **CSV Source:** `TBUniverseProducts.csv` (74 columns, pipe-delimited)
- **Parsing:** Column-by-column extraction into `ProductCsvRecord`
- **Mapping:** Each row → 1-2 products depending on existence
  - **Tier-1:** One per style (`parentProductCode`)
  - **Tier-2:** One per color (`parentProductCode-productColorCode`)
- **Attributes:** Specific columns → specific CT attributes at product/variant level
- **API Calls:** GET-then-create-or-add-variant pattern with retries
- **Error Handling:** Dead-lettering for parse errors, retry logic for API errors
- **Type Routing:** Optional division-based routing to primary/secondary types

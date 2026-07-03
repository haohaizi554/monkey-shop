param(
    [switch]$SkipMaven
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Read-Text {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Required file is missing: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw
}

function Assert-Matches {
    param(
        [string]$Name,
        [string]$Content,
        [string]$Pattern
    )
    if ($Content -notmatch $Pattern) {
        throw "$Name is missing pattern: $Pattern"
    }
}

Write-Host "==> WS1 catalog artifacts"
$docs = Read-Text "docs/product/ws1-catalog.md"
$spuMigration = Read-Text "src/main/resources/db/migration/V19__product_spu_sku.sql"
$categoryMigration = Read-Text "src/main/resources/db/migration/V20__product_category_tree.sql"
$templateMigration = Read-Text "src/main/resources/db/migration/V21__product_attribute_template.sql"
$service = Read-Text "src/main/java/com/example/monkey/product/application/ProductCatalogApplicationService.java"
$controller = Read-Text "src/main/java/com/example/monkey/product/interfaces/CatalogController.java"
$store = Read-Text "src/main/java/com/example/monkey/product/infrastructure/JpaCatalogStore.java"
$cache = Read-Text "src/main/java/com/example/monkey/product/infrastructure/RedisCategoryTreeCache.java"
$spuEntity = Read-Text "src/main/java/com/example/monkey/product/infrastructure/ProductSpu.java"
$rateLimit = Read-Text "src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java"
$snowflake = Read-Text "src/main/java/com/example/monkey/shared/infrastructure/id/SnowflakeIdGenerator.java"
$productDetail = Read-Text "frontend/src/views/ProductDetailView.vue"
$catalogApi = Read-Text "frontend/src/api/catalog.ts"
$jsonLd = Read-Text "frontend/src/seo/product-json-ld.ts"
$workflowTest = Read-Text "src/test/java/com/example/monkey/product/Ws1CatalogWorkflowTest.java"

Assert-Matches "docs" $docs "V19.*V21"
Assert-Matches "spu migration" $spuMigration "CREATE TABLE product_spu"
Assert-Matches "spu migration" $spuMigration "CREATE TABLE product_sku"
Assert-Matches "spu migration" $spuMigration "CONSTRAINT uk_product_sku_spu_code UNIQUE"
Assert-Matches "spu migration" $spuMigration "JSON"
Assert-Matches "category migration" $categoryMigration "CREATE TABLE product_category"
Assert-Matches "category migration" $categoryMigration "level BETWEEN 1 AND 3"
Assert-Matches "category migration" $categoryMigration "fk_product_spu_category"
Assert-Matches "template migration" $templateMigration "CREATE TABLE product_attribute_template"
Assert-Matches "template migration" $templateMigration "fk_product_attribute_template_category"

Assert-Matches "snowflake" $snowflake "implements IdGenerator"
Assert-Matches "snowflake" $snowflake "WORKER_ID_BITS = 5L"
Assert-Matches "snowflake" $snowflake "DATACENTER_ID_BITS = 5L"
Assert-Matches "snowflake" $snowflake "SEQUENCE_BITS = 12L"

Assert-Matches "service" $service "SkuCartesianProductGenerator\.generate"
Assert-Matches "service" $service "idGenerator\.nextId\(\)"
Assert-Matches "service" $service "@Transactional\(readOnly = true\)"
Assert-Matches "service" $service "AuditService\.PRODUCT_SPU_CREATED"
Assert-Matches "service" $service "@WithSpan\(""catalog\.create-spu""\)"
Assert-Matches "controller" $controller "@RequestMapping\(\{""/api/catalog"", ""/api/v1/catalog""\}\)"
Assert-Matches "controller" $controller "hasAuthority\('PRODUCT_MANAGE'\)"
Assert-Matches "store" $store "implements CatalogStore"
Assert-Matches "store" $store "findByActiveTrueOrderByLevelAscSortOrderAscNameAsc"
Assert-Matches "cache" $cache "catalog:category-tree:v1"
Assert-Matches "cache" $cache "Duration\.ofHours\(1\)"
Assert-Matches "spu entity" $spuEntity "EncryptedStringAttributeConverter"
Assert-Matches "rate limit" $rateLimit "path\.startsWith\(""/api/catalog""\)"
Assert-Matches "rate limit" $rateLimit "ApiRateLimitOperation\.SEARCH"

Assert-Matches "frontend detail" $productDetail "el-radio-button"
Assert-Matches "frontend detail" $productDetail "getCatalogSpu"
Assert-Matches "frontend catalog api" $catalogApi "getCategoryTree"
Assert-Matches "frontend catalog api" $catalogApi "getCatalogPrice"
Assert-Matches "json ld" $jsonLd "selectedSku"
Assert-Matches "workflow test" $workflowTest "SPU is the aggregate root"

if (-not $SkipMaven) {
    Write-Host "==> Maven WS1 catalog tests"
    mvn "-Ddependency-check.skip=true" "-Dtest=ProductDomainTest,Ws1CatalogWorkflowTest,SnowflakeIdGeneratorTest" test
    if ($LASTEXITCODE -ne 0) {
        throw "Maven WS1 catalog tests failed with exit code $LASTEXITCODE"
    }
}

Write-Host "WS1 catalog verification completed successfully"

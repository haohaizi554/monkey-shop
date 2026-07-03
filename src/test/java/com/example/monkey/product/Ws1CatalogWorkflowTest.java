package com.example.monkey.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Ws1CatalogWorkflowTest {

    @Test
    void catalogArtifactsWireSpuAggregateRootToInfrastructureAndApi() throws IOException {
        String docs = read("docs/product/ws1-catalog.md");
        String service =
                read("src/main/java/com/example/monkey/product/application/ProductCatalogApplicationService.java");
        String controller = read("src/main/java/com/example/monkey/product/interfaces/CatalogController.java");
        String store = read("src/main/java/com/example/monkey/product/infrastructure/JpaCatalogStore.java");
        String cache = read("src/main/java/com/example/monkey/product/infrastructure/RedisCategoryTreeCache.java");
        String rateLimit = read("src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java");

        assertThat(docs).contains("SPU is the aggregate root").contains("V19").contains("V21");
        assertThat(service)
                .contains("SkuCartesianProductGenerator.generate")
                .contains("idGenerator.nextId()")
                .contains("AuditService.PRODUCT_SPU_CREATED")
                .contains("@WithSpan(\"catalog.create-spu\")")
                .contains("@Transactional(readOnly = true)");
        assertThat(controller)
                .contains("@RequestMapping({\"/api/catalog\", \"/api/v1/catalog\"})")
                .contains("hasAuthority('PRODUCT_MANAGE')");
        assertThat(store)
                .contains("implements CatalogStore")
                .contains("findByActiveTrueOrderByLevelAscSortOrderAscNameAsc");
        assertThat(cache).contains("catalog:category-tree:v1").contains("Duration.ofHours(1)");
        assertThat(rateLimit).contains("path.startsWith(\"/api/catalog\")").contains("ApiRateLimitOperation.SEARCH");
    }

    @Test
    void migrationsDefineRealCatalogSchema() throws IOException {
        String spu = read("src/main/resources/db/migration/V19__product_spu_sku.sql");
        String category = read("src/main/resources/db/migration/V20__product_category_tree.sql");
        String template = read("src/main/resources/db/migration/V21__product_attribute_template.sql");

        assertThat(spu)
                .contains("CREATE TABLE product_spu")
                .contains("CREATE TABLE product_sku")
                .contains("CONSTRAINT uk_product_sku_spu_code UNIQUE")
                .contains("CONSTRAINT fk_product_sku_spu FOREIGN KEY")
                .contains("JSON");
        assertThat(category)
                .contains("CREATE TABLE product_category")
                .contains("level BETWEEN 1 AND 3")
                .contains("fk_product_spu_category")
                .contains("智能手机");
        assertThat(template)
                .contains("CREATE TABLE product_attribute_template")
                .contains("fk_product_attribute_template_category")
                .contains("JSON_ARRAY");
    }

    @Test
    void frontendReusesJsonLdAndExposesSkuSelection() throws IOException {
        String detail = read("frontend/src/views/ProductDetailView.vue");
        String api = read("frontend/src/api/catalog.ts");
        String jsonLd = read("frontend/src/seo/product-json-ld.ts");

        assertThat(detail).contains("getCatalogSpu").contains("el-radio-button").contains("selectedSkuId");
        assertThat(api).contains("getCategoryTree").contains("getCatalogPrice").contains("/catalog/spus/");
        assertThat(jsonLd).contains("selectedSku").contains("selectedSku?.skuCode");
    }

    @Test
    void productDomainStaysFrameworkFree() throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of("org.springframework", "jakarta.persistence", "org.hibernate");

        try (var paths = Files.walk(Path.of("src/main/java/com/example/monkey/product/domain"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                for (String pattern : forbidden) {
                    if (content.contains(pattern)) {
                        violations.add(normalized(path) + " contains " + pattern);
                    }
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String normalized(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}

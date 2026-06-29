package com.example.monkey.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ApiVersioningContractTest {

    @Test
    void springDocOpenApiIsConfiguredForCanonicalV1Api() throws IOException {
        String pom = read("pom.xml");
        String application = read("src/main/resources/application.yml");
        String openApiConfig = read("src/main/java/com/example/monkey/shared/infrastructure/config/OpenApiConfig.java");

        assertThat(pom)
                .contains("<springdoc-openapi.version>2.8.17</springdoc-openapi.version>")
                .contains("<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>");
        assertThat(application)
                .contains("path: /api/v1/openapi")
                .contains("path: /api/v1/docs")
                .contains("- /api/v1/**");
        assertThat(openApiConfig)
                .contains("@OpenAPIDefinition")
                .contains("version = \"v1\"")
                .contains("url = \"/api/v1\"");
    }

    @Test
    void controllersExposeCanonicalV1NounPluralRoutesWhileRetainingLegacySpaRoutes() throws IOException {
        assertThat(read("src/main/java/com/example/monkey/user/interfaces/AddressController.java"))
                .contains("\"/api/address\"")
                .contains("\"/api/v1/addresses\"")
                .contains("@PageableDefault");
        assertThat(read("src/main/java/com/example/monkey/user/interfaces/UserController.java"))
                .contains("\"/api/user\"")
                .contains("\"/api/v1/users\"");
        assertThat(read("src/main/java/com/example/monkey/user/interfaces/PrivacyController.java"))
                .contains("\"/api/user\"")
                .contains("\"/api/v1/users\"");
        assertThat(read("src/main/java/com/example/monkey/user/interfaces/AuthController.java"))
                .contains("\"/api/auth\"")
                .contains("\"/api/v1/auth\"");
        assertThat(read("src/main/java/com/example/monkey/shared/interfaces/storage/UploadController.java"))
                .contains("\"/api/upload\"")
                .contains("\"/api/v1/uploads\"");
        assertThat(read("src/main/java/com/example/monkey/product/interfaces/MonkeyController.java"))
                .contains("\"/api/monkeys\"")
                .contains("\"/api/v1/monkeys\"")
                .contains("Pageable pageable");
        assertThat(read("src/main/java/com/example/monkey/order/interfaces/OrderController.java"))
                .contains("\"/api/orders\"")
                .contains("\"/api/v1/orders\"")
                .contains("Idempotency-Key")
                .contains("PageResponseDto<OrderResponseDto>");
        assertThat(read("src/main/java/com/example/monkey/admin/interfaces/StatsController.java"))
                .contains("\"/api/stats\"")
                .contains("\"/api/v1/stats\"");
    }

    @Test
    void collectionReadApisUsePageableWithoutLeakingSpringPageJson() throws IOException {
        assertThat(read("src/main/java/com/example/monkey/shared/application/dto/PageResponseDto.java"))
                .contains("record PageResponseDto")
                .doesNotContain("org.springframework.data.domain.Page")
                .doesNotContain("Page<T> page");
        assertThat(read("src/main/java/com/example/monkey/product/application/MonkeyService.java"))
                .contains("findMonkeys(ProductPageQuery pageQuery)")
                .doesNotContain("org.springframework.data.domain.Pageable")
                .contains("PageResponseDto.from");
        assertThat(read("src/main/java/com/example/monkey/order/application/OrderService.java"))
                .contains("findOrdersForUser(Long userId, OrderPageQuery pageQuery)")
                .contains("findAllOrders(OrderPageQuery pageQuery)")
                .doesNotContain("org.springframework.data.domain.Pageable");
        assertThat(read("src/main/java/com/example/monkey/user/application/AddressService.java"))
                .contains("findAddressesForUser(Long userId, AddressPageQuery pageQuery)")
                .doesNotContain("org.springframework.data.domain.Pageable");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}

package com.example.monkey.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.cart.application.CartApplicationService;
import com.example.monkey.cart.interfaces.CartController;
import com.example.monkey.logistics.application.LogisticsApplicationService;
import com.example.monkey.logistics.interfaces.LogisticsController;
import com.example.monkey.membership.application.MembershipApplicationService;
import com.example.monkey.membership.interfaces.MembershipController;
import com.example.monkey.order.application.OrderApplicationService;
import com.example.monkey.order.application.OrderService;
import com.example.monkey.order.interfaces.OrderController;
import com.example.monkey.payment.application.PaymentApplicationService;
import com.example.monkey.payment.interfaces.PaymentAdminController;
import com.example.monkey.payment.interfaces.PaymentController;
import com.example.monkey.product.application.MonkeyService;
import com.example.monkey.product.interfaces.MonkeyController;
import com.example.monkey.risk.application.RiskApplicationService;
import com.example.monkey.shared.application.security.ApiRateLimitApplicationService;
import com.example.monkey.shared.domain.security.TrustedProxyPolicy;
import com.example.monkey.shared.infrastructure.config.OpenApiConfig;
import com.example.monkey.shared.interfaces.web.VisitInterceptor;
import com.example.monkey.user.application.AddressApplicationService;
import com.example.monkey.user.interfaces.AddressController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocPageableConfiguration;
import org.springdoc.core.configuration.SpringDocSortConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = {
            OrderController.class,
            PaymentController.class,
            PaymentAdminController.class,
            CartController.class,
            LogisticsController.class,
            MembershipController.class,
            MonkeyController.class,
            AddressController.class
        })
@AutoConfigureMockMvc(addFilters = false)
@Import(OpenApiConfig.class)
@ImportAutoConfiguration({
    SpringDocConfiguration.class,
    SpringDocConfigProperties.class,
    SpringDocWebMvcConfiguration.class,
    SpringDocPageableConfiguration.class,
    SpringDocSortConfiguration.class
})
@TestPropertySource(
        properties = {
            "springdoc.api-docs.path=/api/v1/openapi",
            "springdoc.paths-to-match=/api/v1/**",
            "springdoc.cache.disabled=true"
        })
@MockitoBean(
        types = {
            OrderApplicationService.class,
            OrderService.class,
            RiskApplicationService.class,
            PaymentApplicationService.class,
            CartApplicationService.class,
            LogisticsApplicationService.class,
            MembershipApplicationService.class,
            MonkeyService.class,
            AddressApplicationService.class,
            VisitInterceptor.class,
            ApiRateLimitApplicationService.class,
            TrustedProxyPolicy.class
        })
class OpenApiRuntimeContractTest {

    private static final List<String> IDEMPOTENT_POST_PATHS = List.of(
            "/api/v1/orders/create",
            "/api/v1/payments/pay",
            "/api/v1/payments/refund",
            "/api/v1/payments/admin/refund",
            "/api/v1/cart/checkout",
            "/api/v1/logistics/shipments",
            "/api/v1/membership/check-in",
            "/api/v1/membership/points/earn",
            "/api/v1/membership/points/redeem");

    private static final List<String> PAGED_GET_PATHS =
            List.of("/api/v1/orders/my", "/api/v1/orders/all", "/api/v1/monkeys", "/api/v1/addresses");

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final RiskApplicationService riskApplicationService;
    private final OrderApplicationService orderApplicationService;

    @Autowired
    OpenApiRuntimeContractTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            RiskApplicationService riskApplicationService,
            OrderApplicationService orderApplicationService) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.riskApplicationService = riskApplicationService;
        this.orderApplicationService = orderApplicationService;
    }

    @Test
    void generatedDocumentUsesRootServerWithCanonicalPathsExactlyOnce() throws Exception {
        JsonNode document = document();

        assertThat(document.at("/servers/0/url").asText()).isEqualTo("/");
        assertThat(document.at(pathPointer("/api/v1/orders/create", "post")).isMissingNode())
                .isFalse();
        assertThat(document.at(pathPointer("/api/v1/api/v1/orders/create", "post"))
                        .isMissingNode())
                .isTrue();
    }

    @Test
    void generatedDocumentMarksEveryIdempotencyHeaderRequired() throws Exception {
        JsonNode document = document();

        for (String path : IDEMPOTENT_POST_PATHS) {
            JsonNode parameters = document.at(pathPointer(path, "post") + "/parameters");
            JsonNode header = StreamSupport.stream(parameters.spliterator(), false)
                    .filter(parameter ->
                            "Idempotency-Key".equals(parameter.path("name").asText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing Idempotency-Key for " + path));
            assertThat(header.path("in").asText()).as(path).isEqualTo("header");
            assertThat(header.path("required").asBoolean()).as(path).isTrue();
            assertThat(header.at("/schema/minLength").asInt()).as(path).isEqualTo(1);
        }
    }

    @Test
    void generatedCollectionResponsesExposeOnePageEnvelopeSchema() throws Exception {
        JsonNode document = document();

        for (String path : PAGED_GET_PATHS) {
            JsonNode content = document.at(pathPointer(path, "get") + "/responses/200/content");
            assertThat(content.isObject()).as(path).isTrue();
            JsonNode schema = content.elements().next().path("schema");
            assertThat(schema.isMissingNode()).as(path).isFalse();
            assertThat(schema.path("$ref").asText()).as(path).contains("ResultPageResponseDto");
            assertThat(schema.has("oneOf")).as(path).isFalse();
        }
    }

    @Test
    void generatedDocumentDoesNotExposeAuthenticatedPrincipalAsAQueryParameter() throws Exception {
        JsonNode document = document();

        assertThat(document.toString()).doesNotContain("\"name\":\"currentUser\"");
    }

    @Test
    void blankIdempotencyHeaderIsRejectedBeforeRiskOrOrderWork() throws Exception {
        mockMvc.perform(post("/api/v1/orders/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", " ")
                        .content("{\"monkeyId\":3,\"addressId\":5}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("Idempotency-Key"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("NotBlank"));

        verifyNoInteractions(riskApplicationService, orderApplicationService);
    }

    private JsonNode document() throws Exception {
        String body = mockMvc.perform(get("/api/v1/openapi"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private static String pathPointer(String path, String method) {
        return "/paths/" + path.replace("/", "~1") + "/" + method;
    }
}

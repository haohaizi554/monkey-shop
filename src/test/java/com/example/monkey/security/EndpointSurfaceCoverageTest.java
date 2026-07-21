package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EndpointSurfaceCoverageTest {

    private static final Set<String> CONSUMER_UI = Set.of(
            "AddressController#addAddress",
            "AddressController#delete",
            "AddressController#myAddresses",
            "AddressController#setDefault",
            "AddressController#update",
            "AuthController#getCaptcha",
            "AuthController#getCaptchaConfig",
            "AuthController#login",
            "AuthController#passwordPolicy",
            "AuthController#refresh",
            "AuthController#register",
            "AuthController#requestPasswordReset",
            "AuthController#resetPassword",
            "CartController#addItem",
            "CartController#cart",
            "CartController#checkout",
            "CartController#directCheckout",
            "CartController#previewCheckout",
            "CartController#removeItem",
            "CartController#selectItem",
            "CartController#updateItem",
            "CatalogController#categoryTree",
            "CatalogController#getSpu",
            "CatalogController#quotePrice",
            "InventoryController#stocks",
            "LogisticsController#findByOrder",
            "LogisticsController#findByTrackingNo",
            "LogisticsController#parseAddress",
            "LogisticsController#quoteFreight",
            "MembershipController#addCollection",
            "MembershipController#checkIn",
            "MembershipController#dashboard",
            "MembershipController#recordBrowse",
            "MembershipController#redeemPoints",
            "MembershipController#removeCollection",
            "MembershipController#verifyIdentity",
            "MonkeyController#getMonkeys",
            "OrderController#applyReturn",
            "OrderController#createOrder",
            "OrderController#hideOrder",
            "OrderController#myOrders",
            "OrderController#order",
            "OrderController#receiveOrder",
            "OrderController#receiveShipment",
            "OrderController#reviewOrder",
            "OrderController#reviews",
            "OrderController#shipments",
            "OrderController#userShipReturn",
            "PaymentController#createPayment",
            "PaymentController#findByOrder",
            "PaymentController#refund",
            "PrivacyController#forgetMe",
            "RiskController#assess",
            "SearchController#hotKeywords",
            "SearchController#products",
            "SearchController#recommendations",
            "SearchController#recordConversion",
            "SearchController#suggestions",
            "SearchController#upsertProfile",
            "SecurityFilterChain#logout",
            "TrackingController#recordEvent",
            "UploadController#upload",
            "UserController#getCaptcha",
            "UserController#getCurrentUser",
            "UserController#getProfile",
            "UserController#updateAvatar",
            "UserController#updatePassword");

    private static final Set<String> ADMIN_UI = Set.of(
            "InventoryController#reconcile",
            "InventoryController#release",
            "InventoryController#reserve",
            "MarketingController#claimCoupon",
            "MarketingController#createSeckillOrder",
            "MarketingController#joinGroupBuy",
            "MarketingController#quotePrice",
            "MarketingController#redeemCoupon",
            "MarketingController#returnCoupon",
            "MembershipController#adminChangeLevel",
            "MembershipController#adminDashboard",
            "MembershipController#adminEarnPoints",
            "MembershipController#scanPriceDrops",
            "MonkeyController#addMonkey",
            "MonkeyController#deleteMonkey",
            "MonkeyController#updateMonkey",
            "OrderController#adminShipments",
            "OrderController#approveReturn",
            "OrderController#confirmReturn",
            "OrderController#getAllOrders",
            "OrderController#shipOrder",
            "PaymentAdminController#findByOrder",
            "PaymentAdminController#refund",
            "PaymentController#reconcile",
            "RiskController#resolveReview",
            "RiskController#reviewQueue",
            "StatsController#getAuditTrace",
            "StatsController#getStats",
            "TenantAdminController#bills",
            "TenantAdminController#configs",
            "TenantAdminController#createTenant",
            "TenantAdminController#dashboard",
            "TenantAdminController#downgradeTenant",
            "TenantAdminController#exports",
            "TenantAdminController#generateBill",
            "TenantAdminController#renewTenant",
            "TenantAdminController#requestExport",
            "TenantAdminController#tenants",
            "TenantAdminController#upsertConfig",
            "TrackingController#currentUserProfile",
            "TrackingController#dashboard",
            "TrackingController#productProfile");

    private static final Map<String, String> MACHINE_ONLY = Map.of(
            "LogisticsController#webhook", "signed carrier webhook; server boundary only",
            "PaymentController#callback", "signed payment-provider callback; server boundary only");

    private static final Map<String, String> API_ONLY = Map.ofEntries(
            Map.entry(
                    "CatalogController#createSpu",
                    "versioned catalog write retained for external catalog integrations"),
            Map.entry(
                    "CatalogController#transitionStatus",
                    "versioned catalog lifecycle retained for external catalog integrations"),
            Map.entry("InventoryController#compensate", "order-orchestration compensation; no direct browser control"),
            Map.entry("InventoryController#deduct", "order-orchestration deduction; no direct browser control"),
            Map.entry(
                    "LogisticsController#createShipment",
                    "legacy owner shipment contract retained without a browser client"),
            Map.entry(
                    "MembershipController#changeLevel",
                    "legacy self-targeting admin contract retained for compatibility"),
            Map.entry(
                    "MembershipController#earnPoints",
                    "legacy self-targeting admin contract retained for compatibility"),
            Map.entry(
                    "TrackingController#funnel",
                    "analytics integration endpoint; dashboard uses the aggregate response"),
            Map.entry(
                    "TrackingController#userProfile", "support integration endpoint; no arbitrary-user browser lookup"),
            Map.entry(
                    "UploadController#uploadAvatar",
                    "typed upload compatibility endpoint; UI uses the guarded generic upload"),
            Map.entry(
                    "UploadController#uploadProduct",
                    "typed upload compatibility endpoint; UI uses the guarded generic upload"));

    @Test
    void everyCanonicalEndpointHasAnExplicitProductSurface() throws IOException {
        List<ControllerEndpointInventory.Endpoint> endpoints = ControllerEndpointInventory.canonicalApiEndpoints();
        Set<String> handlers = endpoints.stream()
                .map(ControllerEndpointInventory.Endpoint::handlerKey)
                .collect(Collectors.toSet());
        Map<String, Surface> classifications = classifications();

        assertThat(classifications.keySet())
                .as("stale or missing handler classifications")
                .isEqualTo(handlers);
        assertThat(endpoints)
                .allSatisfy(endpoint -> assertThat(classifications.get(endpoint.handlerKey()))
                        .as(endpoint.key())
                        .isNotNull());

        Path report = Path.of("target", "reports", "endpoint-surface-inventory.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, renderReport(endpoints, classifications), StandardCharsets.UTF_8);

        assertThat(endpoints).hasSize(124);
        assertThat(classifications.values()).contains(Surface.CONSUMER_UI, Surface.ADMIN_UI, Surface.MACHINE_ONLY);
    }

    private static Map<String, Surface> classifications() {
        Map<String, Surface> result = new HashMap<>();
        addClassifications(result, Surface.CONSUMER_UI, CONSUMER_UI);
        addClassifications(result, Surface.ADMIN_UI, ADMIN_UI);
        addClassifications(result, Surface.MACHINE_ONLY, MACHINE_ONLY.keySet());
        addClassifications(result, Surface.API_ONLY, API_ONLY.keySet());
        return Map.copyOf(result);
    }

    private static void addClassifications(Map<String, Surface> result, Surface surface, Set<String> handlers) {
        for (String handler : handlers) {
            Surface previous = result.put(handler, surface);
            if (previous != null) {
                throw new IllegalStateException(handler + " is classified as both " + previous + " and " + surface);
            }
        }
    }

    private static String renderReport(
            List<ControllerEndpointInventory.Endpoint> endpoints, Map<String, Surface> classifications) {
        Map<Surface, Long> counts = endpoints.stream()
                .collect(Collectors.groupingBy(
                        endpoint -> classifications.get(endpoint.handlerKey()),
                        () -> new EnumMap<>(Surface.class),
                        Collectors.counting()));
        StringBuilder report = new StringBuilder()
                .append("# Stage 9C Endpoint Surface Inventory\n\n")
                .append("Generated from controller annotations. Canonical endpoints: ")
                .append(endpoints.size())
                .append(".\n\n");
        for (Surface surface : Surface.values()) {
            report.append("- ")
                    .append(surface)
                    .append(": ")
                    .append(counts.getOrDefault(surface, 0L))
                    .append('\n');
        }
        report.append("\n| Method | Canonical path | Surface | Handler | Authorization | Evidence |\n")
                .append("| --- | --- | --- | --- | --- | --- |\n");
        for (ControllerEndpointInventory.Endpoint endpoint : endpoints) {
            Surface surface = classifications.get(endpoint.handlerKey());
            report.append("| ")
                    .append(endpoint.method())
                    .append(" | `")
                    .append(endpoint.path())
                    .append("` | ")
                    .append(surface)
                    .append(" | `")
                    .append(endpoint.handlerKey())
                    .append("` | `")
                    .append(endpoint.authorization().replace("|", "\\|"))
                    .append("` | ")
                    .append(evidence(endpoint, surface))
                    .append(" |\n");
        }
        return report.toString();
    }

    private static String evidence(ControllerEndpointInventory.Endpoint endpoint, Surface surface) {
        if (surface == Surface.MACHINE_ONLY) {
            return MACHINE_ONLY.get(endpoint.handlerKey());
        }
        if (surface == Surface.API_ONLY) {
            return API_ONLY.get(endpoint.handlerKey());
        }
        return apiModule(endpoint.controller()) + " -> " + routeFamily(endpoint.controller(), surface);
    }

    private static String apiModule(String controller) {
        return switch (controller) {
            case "AddressController", "PrivacyController", "UserController" -> "`frontend/src/api/user.ts`";
            case "AuthController" -> "`frontend/src/api/auth.ts`";
            case "CartController" -> "`frontend/src/api/cart.ts`";
            case "CatalogController", "MonkeyController", "UploadController" -> "`frontend/src/api/catalog.ts`";
            case "InventoryController" -> "`frontend/src/api/inventory.ts`";
            case "LogisticsController" -> "`frontend/src/api/logistics.ts`";
            case "MarketingController" -> "`frontend/src/api/marketing.ts`";
            case "MembershipController" -> "`frontend/src/api/membership.ts`";
            case "OrderController" -> "`frontend/src/api/orders.ts`";
            case "PaymentAdminController", "PaymentController" -> "`frontend/src/api/payments.ts`";
            case "RiskController" -> "`frontend/src/api/risk.ts`";
            case "SearchController" -> "`frontend/src/api/search.ts`";
            case "SecurityFilterChain" -> "`frontend/src/api/auth.ts`";
            case "StatsController" -> "`frontend/src/api/admin.ts`";
            case "TenantAdminController" -> "`frontend/src/api/tenant.ts`";
            case "TrackingController" -> "`frontend/src/api/tracking.ts`";
            default -> throw new IllegalStateException("No frontend API module for " + controller);
        };
    }

    private static String routeFamily(String controller, Surface surface) {
        if (surface == Surface.ADMIN_UI) {
            return "`/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants`";
        }
        return switch (controller) {
            case "AuthController" -> "`/login`";
            case "AddressController", "PrivacyController", "UserController", "UploadController" -> "`/profile`";
            case "CartController" -> "`/cart` and `/checkout`";
            case "LogisticsController" -> "`/logistics/:orderId`";
            case "MembershipController" -> "`/membership`";
            case "OrderController" -> "`/orders` and order detail workflows";
            case "PaymentController" -> "`/payment/:orderId` and return workflows";
            case "SecurityFilterChain" -> "consumer and admin shell sign-out controls";
            case "SearchController" -> "`/search` and `/recommendations`";
            case "TrackingController" -> "shared page tracking";
            default -> "`/shop` and product workflows";
        };
    }

    private enum Surface {
        CONSUMER_UI,
        ADMIN_UI,
        MACHINE_ONLY,
        SCHEDULED_INTERNAL,
        API_ONLY
    }
}

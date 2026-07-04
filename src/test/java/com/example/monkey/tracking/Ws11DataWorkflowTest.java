package com.example.monkey.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws11DataWorkflowTest {

    @Test
    void trackingArtifactsWireSdkProfilesDashboardMetricsAndFrontend() throws IOException {
        String docs = read("docs/tracking/ws11.md");
        String eventMigration = read("src/main/resources/db/migration/V42__tracking_event.sql");
        String profileMigration = read("src/main/resources/db/migration/V43__user_profile_tag.sql");
        String service = read("src/main/java/com/example/monkey/tracking/application/TrackingApplicationService.java");
        String store = read("src/main/java/com/example/monkey/tracking/infrastructure/JpaTrackingStore.java");
        String controller = read("src/main/java/com/example/monkey/tracking/interfaces/TrackingController.java");
        String filter = read("src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java");
        String securityConfig =
                read("src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java");
        String audit = read("src/main/java/com/example/monkey/shared/application/observability/AuditService.java");
        String metrics =
                read("src/main/java/com/example/monkey/order/application/observability/BusinessMetricsService.java");
        String visits =
                read("src/main/java/com/example/monkey/shared/application/observability/VisitMetricsService.java");
        String grafana = read("helm/monkeyshop/templates/grafana-dashboard.yaml");
        String frontendApi = read("frontend/src/api/tracking.ts");
        String trackingSdk = read("frontend/src/TrackingSdk.ts");
        String dashboardView = read("frontend/src/views/DashboardView.vue");
        String script = read("scripts/verify-ws11-data.ps1");

        assertThat(docs).contains("tracking SDK", "user profile", "funnel", "Tink");
        assertThat(eventMigration).contains("CREATE TABLE tracking_event", "TRACKING_ADMIN", "idx_tracking_event");
        assertThat(profileMigration)
                .contains("CREATE TABLE user_profile_tag", "encrypted_profile_summary", "CREATE TABLE product_profile");
        assertThat(service)
                .contains(
                        "@WithSpan(\"tracking.event-record\")",
                        "VisitMetricsService",
                        "BusinessMetricsService",
                        "idGenerator.nextId()");
        assertThat(store).contains("PiiCryptoService", "app.tracking.store", "piiCryptoService.encrypt");
        assertThat(controller).contains("/api/tracking", "/events", "/dashboard", "/profile/me");
        assertThat(filter).contains("/api/tracking/internal/pixel", "ApiRateLimitOperation.TRACKING");
        assertThat(securityConfig).contains("TRACKING_READ", "TRACKING_ADMIN");
        assertThat(audit).contains("TRACKING_EVENT_RECORDED", "USER_PROFILE_TAG_UPDATED", "PRODUCT_PROFILE_UPDATED");
        assertThat(metrics).contains("tracking.event", "tracking.funnel");
        assertThat(visits).contains("recordClientPageView");
        assertThat(grafana).contains("5s", "tracking_event_total", "tracking_funnel");
        assertThat(frontendApi).contains("recordTrackingEvent", "trackingDashboard");
        assertThat(trackingSdk).contains("installTracking", "PAGE_VIEW", "CLICK", "PRODUCT_VIEW");
        assertThat(dashboardView).contains("DashboardView", "Realtime Dashboard", "Funnel");
        assertThat(script).contains("TrackingApplicationServiceTest", "WS11 data verification completed successfully");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}

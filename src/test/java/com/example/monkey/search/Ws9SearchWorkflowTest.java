package com.example.monkey.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Ws9SearchWorkflowTest {

    @Test
    void searchArtifactsWireDiscoveryRecommendationProfileAndFrontend() throws IOException {
        String docs = read("docs/search/ws9.md");
        String service = read("src/main/java/com/example/monkey/search/application/SearchApplicationService.java");
        String controller = read("src/main/java/com/example/monkey/search/interfaces/SearchController.java");
        String redis = read("src/main/java/com/example/monkey/search/infrastructure/RedisSearchActivityStore.java");
        String migration = read("src/main/resources/db/migration/V38__user_search_profile.sql");
        String filter = read("src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java");
        String metrics =
                read("src/main/java/com/example/monkey/order/application/observability/BusinessMetricsService.java");
        String searchView = read("frontend/src/views/SearchView.vue");
        String recommendView = read("frontend/src/views/RecommendView.vue");
        String script = read("scripts/verify-ws9-search.ps1");

        assertThat(docs).contains("Search suggestions", "hot keyword", "Tink encryption");
        assertThat(service).contains("@WithSpan(\"search.products\")", "MembershipActivityStore", "recentPurchases");
        assertThat(controller).contains("/api/search", "/recommendations", "/conversions");
        assertThat(redis).contains("search:hot-keywords", "search:suggest:", "Duration.ofMinutes(5)");
        assertThat(migration).contains("user_search_profile", "interest_profile_hmac", "SEARCH_READ");
        assertThat(filter).contains("/api/search/internal/hot", "ApiRateLimitOperation.SEARCH");
        assertThat(metrics).contains("search.conversion", "recordSearchConversion");
        assertThat(searchView).contains("searchApi.searchProducts", "recordSearchConversion");
        assertThat(recommendView).contains("searchApi.recommendations", "updateSearchProfile");
        assertThat(script).contains("Ws9SearchWorkflowTest", "WS9 search verification completed successfully");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}

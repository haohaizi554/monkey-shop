package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class StaticAssetIntegrityTest {

    private static final List<Path> LEGACY_STATIC_PATHS = List.of(
            Path.of("src/main/resources/static/index.html"),
            Path.of("src/main/resources/static/shop.html"),
            Path.of("src/main/resources/static/orders.html"),
            Path.of("src/main/resources/static/profile.html"),
            Path.of("src/main/resources/static/admin.html"),
            Path.of("src/main/resources/static/css"),
            Path.of("src/main/resources/static/js"));
    private static final Pattern INLINE_EVENT_HANDLER = Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern CDN_SCRIPT =
            Pattern.compile("https://(?:unpkg\\.com|cdn\\.jsdelivr\\.net|fonts\\.googleapis\\.com)");

    @Test
    void springStaticResourcesNoLongerCarryLegacyCdnPages() {
        for (Path path : LEGACY_STATIC_PATHS) {
            assertThat(legacyPathContainsFiles(path))
                    .as(path + " should not contain legacy static files")
                    .isFalse();
        }
        assertThat(Path.of("src/main/resources/static/images/default_avatar.png"))
                .exists();
        assertThat(Path.of("src/main/resources/static/images/default_product.png"))
                .exists();
    }

    @Test
    void viteSourceDoesNotReintroduceFloatingCdnsOrInlineHandlers() throws IOException {
        List<Path> frontendFiles;
        try (var paths = Files.walk(Path.of("frontend"))) {
            frontendFiles = paths.filter(Files::isRegularFile)
                    .filter(StaticAssetIntegrityTest::isFrontendSourceFile)
                    .toList();
        }

        for (Path path : frontendFiles) {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            assertThat(CDN_SCRIPT.matcher(content).find())
                    .as(path + " should not depend on legacy CDN assets")
                    .isFalse();
            assertThat(INLINE_EVENT_HANDLER.matcher(content).find())
                    .as(path + " should not contain inline DOM event handlers")
                    .isFalse();
        }
    }

    @Test
    void viteRouterProvidesHistoryModeStorefrontRoutesAndGuardsPrivatePages() throws IOException {
        String router = Files.readString(Path.of("frontend/src/router/index.ts"), StandardCharsets.UTF_8);

        assertThat(router)
                .contains("createWebHistory()")
                .contains("{ path: '/', redirect: '/shop' }")
                .contains("path: '/login'")
                .contains("path: '/shop'")
                .contains("path: '/orders'")
                .contains("path: '/profile'")
                .contains("path: '/admin'")
                .contains("requiresAuth: true")
                .contains("requiresAdmin: true")
                .contains("auth.loadCurrentUser()")
                .contains("return { path: '/login'");
    }

    @Test
    void viteHttpClientKeepsJwtInCookiesAndAddsCsrfForUnsafeRequests() throws IOException {
        String http = Files.readString(Path.of("frontend/src/api/http.ts"), StandardCharsets.UTF_8);
        String authStore = Files.readString(Path.of("frontend/src/stores/auth.ts"), StandardCharsets.UTF_8);

        assertThat(http)
                .contains("baseURL: '/api/v1'")
                .contains("withCredentials: true")
                .contains("unsafeMethods")
                .contains("csrfHeader()")
                .contains("status === 401")
                .contains("http.post('/auth/refresh')")
                .contains("result.code === 'OK'")
                .contains("detail?.traceId");
        assertThat(authStore)
                .doesNotContain("accessToken")
                .doesNotContain("localStorage")
                .doesNotContain("sessionStorage");
    }

    @Test
    void viteLoginSupportsTurnstileAdminMfaAndOtpPasswordReset() throws IOException {
        String login = Files.readString(Path.of("frontend/src/views/LoginView.vue"), StandardCharsets.UTF_8);
        String humanVerification =
                Files.readString(Path.of("frontend/src/components/HumanVerification.vue"), StandardCharsets.UTF_8);

        assertThat(login)
                .contains("HumanVerification")
                .contains("action=\"login\"")
                .contains("action=\"register\"")
                .contains("action=\"password-reset-request\"")
                .contains("action=\"password-reset\"")
                .contains("showAdminMfa")
                .contains("loginForm.totp")
                .contains("resetForm.otp")
                .contains("resetForm.emailToken")
                .contains("requestResetCode")
                .contains("authApi.resetPassword(resetForm)");
        assertThat(humanVerification)
                .contains("https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit")
                .contains("action: props.action")
                .contains("callback: (token: string)");
    }

    @Test
    void dockerImageBuildsAndPackagesViteSpaInsteadOfLegacyStaticPages() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"), StandardCharsets.UTF_8);

        assertThat(dockerfile)
                .contains("FROM node:24-bookworm-slim AS frontend-build")
                .contains("COPY frontend/package*.json ./")
                .contains("RUN npm ci")
                .contains("RUN npm run build")
                .contains(
                        "rm -rf src/main/resources/static/*.html src/main/resources/static/css src/main/resources/static/js")
                .contains("COPY --from=frontend-build /workspace/frontend/dist/ ./src/main/resources/static/")
                .contains("mvn --batch-mode -DskipTests package");
    }

    @Test
    void mavenPackagesPrebuiltFrontendDistWhenAvailable() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertThat(pom)
                .contains("<directory>frontend/dist</directory>")
                .contains("<targetPath>static</targetPath>")
                .contains("<filtering>false</filtering>");
    }

    @Test
    void ciRunsFrontendAccessibilityAndLighthouseGates() throws IOException {
        String workflow = Files.readString(Path.of(".github/workflows/ci.yaml"), StandardCharsets.UTF_8);
        String lighthouse = Files.readString(Path.of("frontend/scripts/lighthouse.mjs"), StandardCharsets.UTF_8);
        String packageJson = Files.readString(Path.of("frontend/package.json"), StandardCharsets.UTF_8);

        assertThat(workflow)
                .contains("working-directory: frontend")
                .contains("npm run audit")
                .contains("npm run format")
                .contains("npx playwright install --with-deps chromium")
                .contains("npm run test:a11y")
                .contains("CHROME_PATH=$(node -e")
                .contains("chromium.executablePath()")
                .contains("npm run test:lighthouse")
                .contains("frontend/lighthouse-report.json");
        assertThat(lighthouse)
                .contains("chromePath: process.env.CHROME_PATH || undefined")
                .contains("score < 0.95")
                .contains("lcp > 2500");
        assertThat(packageJson)
                .contains("\"audit\": \"npm audit --audit-level=high --registry=https://registry.npmjs.org\"");
    }

    private static boolean isFrontendSourceFile(Path path) {
        String normalized = path.normalize().toString().replace('\\', '/');
        if (normalized.contains("/node_modules/")
                || normalized.contains("/dist/")
                || normalized.contains("/dist-ssr/")
                || normalized.contains("/test-results/")
                || normalized.contains("/playwright-report/")
                || normalized.contains("/coverage/")
                || normalized.endsWith("lighthouse-report.json")
                || normalized.endsWith("package-lock.json")) {
            return false;
        }
        String lower = normalized.toLowerCase();
        return lower.endsWith(".html")
                || lower.endsWith(".ts")
                || lower.endsWith(".vue")
                || lower.endsWith(".css")
                || lower.endsWith(".json");
    }

    private static boolean legacyPathContainsFiles(Path path) {
        if (!Files.exists(path)) {
            return false;
        }
        if (Files.isRegularFile(path)) {
            return true;
        }
        try (var paths = Files.walk(path)) {
            return paths.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to inspect legacy static path " + path, e);
        }
    }
}

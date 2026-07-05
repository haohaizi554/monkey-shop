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
                .contains("path: '/shop/:productId'")
                .contains("ProductDetailView.vue")
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
        String authApi = Files.readString(Path.of("frontend/src/api/auth.ts"), StandardCharsets.UTF_8);
        String authStore = Files.readString(Path.of("frontend/src/stores/auth.ts"), StandardCharsets.UTF_8);
        String types = Files.readString(Path.of("frontend/src/types.ts"), StandardCharsets.UTF_8);
        String cookieAuthSource = http + authApi + authStore + types;

        assertThat(http)
                .contains("baseURL: '/api/v1'")
                .contains("withCredentials: true")
                .contains("unsafeMethods")
                .contains("csrfHeader()")
                .contains("idempotencyKeyHeader = 'Idempotency-Key'")
                .contains("config.headers.set(idempotencyKeyHeader, createTraceId())")
                .contains("status === 401")
                .contains("http.post('/auth/refresh')")
                .contains("result.code === 'OK'")
                .contains("detail?.traceId");
        assertThat(authStore)
                .contains("await loadCurrentUser()")
                .doesNotContain("accessToken")
                .doesNotContain("refreshToken")
                .doesNotContain("localStorage")
                .doesNotContain("sessionStorage");
        assertThat(cookieAuthSource)
                .doesNotContain("Authorization")
                .doesNotContain("Bearer")
                .doesNotContain("accessToken")
                .doesNotContain("refreshToken")
                .doesNotContain("sessionStorage");
    }

    @Test
    void viteShopCheckoutDebouncesSubmissionsAndKeepsOrderIdempotency() throws IOException {
        String shop = Files.readString(Path.of("frontend/src/views/ShopView.vue"), StandardCharsets.UTF_8);
        String checkout = Files.readString(Path.of("frontend/src/composables/useCheckout.ts"), StandardCharsets.UTF_8);
        String ordersApi = Files.readString(Path.of("frontend/src/api/orders.ts"), StandardCharsets.UTF_8);

        assertThat(shop)
                .contains("useCheckout({ afterOrderCreated: loadMonkeys, notify: showNotice })")
                .contains(":disabled=\"submittingOrder\"")
                .contains("openingCheckoutId")
                .contains(":disabled=\"monkey.stock <= 0 || openingCheckoutId !== null\"")
                .contains("openingCheckoutId === monkey.id")
                .contains("`/shop/${monkey.id}`");
        assertThat(checkout)
                .contains("submitTimer")
                .contains("setTimeout(() =>")
                .contains("submittingOrder")
                .contains("if (submittingOrder.value)")
                .contains("await createOrder(selectedMonkey.value.id, selectedAddressId.value)")
                .contains("afterOrderCreated")
                .doesNotContain("useDebounceFn");
        assertThat(ordersApi)
                .contains("'Idempotency-Key'")
                .contains("crypto.randomUUID()")
                .contains("method: 'POST'");
    }

    @Test
    void viteCriticalShopShellAvoidsElementPlusStartupAndPreloads() throws IOException {
        String shop = Files.readString(Path.of("frontend/src/views/ShopView.vue"), StandardCharsets.UTF_8);
        String shell = Files.readString(Path.of("frontend/src/components/AppShell.vue"), StandardCharsets.UTF_8);
        String config = Files.readString(Path.of("frontend/vite.config.ts"), StandardCharsets.UTF_8);

        assertThat(shop).doesNotContain("from 'element-plus'");
        assertThat(shell).doesNotContain("from 'element-plus'");
        assertThat(config)
                .contains("resolveDependencies(_filename, deps)")
                .contains("!dep.includes('element-')")
                .doesNotContain("element-plus':")
                .doesNotContain("@element-plus");
    }

    @Test
    void viteOrderAndAdminWriteActionsDebounceAndExposeLoadingStates() throws IOException {
        String orders = Files.readString(Path.of("frontend/src/views/OrdersView.vue"), StandardCharsets.UTF_8);
        String admin = Files.readString(Path.of("frontend/src/views/AdminView.vue"), StandardCharsets.UTF_8);

        assertThat(orders)
                .contains("useDebounceFn")
                .contains("actionInProgress")
                .contains(":loading=\"actionInProgress === orderActionKey")
                .contains(":disabled=\"actionInProgress !== null\"")
                .contains("ordersApi.receiveOrder")
                .contains("ordersApi.applyReturn")
                .contains("ordersApi.shipReturn")
                .contains("ordersApi.hideOrder");
        assertThat(admin)
                .contains("useDebounceFn")
                .contains("savingProduct")
                .contains("deletingProductId")
                .contains("orderActionInProgress")
                .contains(":loading=\"savingProduct\"")
                .contains(":loading=\"deletingProductId === row.id\"")
                .contains(":loading=\"orderActionInProgress === orderActionKey")
                .contains("ordersApi.shipOrder")
                .contains("ordersApi.approveReturn")
                .contains("ordersApi.confirmReturn");
    }

    @Test
    void viteShellKeepsChineseEnglishLocaleAndDarkModeReachable() throws IOException {
        String shell = Files.readString(Path.of("frontend/src/components/AppShell.vue"), StandardCharsets.UTF_8);
        String locales = Files.readString(Path.of("frontend/src/locales/index.ts"), StandardCharsets.UTF_8);
        String theme = Files.readString(Path.of("frontend/src/stores/theme.ts"), StandardCharsets.UTF_8);
        String a11y = Files.readString(Path.of("frontend/tests/a11y.spec.ts"), StandardCharsets.UTF_8);

        assertThat(shell)
                .contains("aria-label=\"Switch language\"")
                .contains("monkeyshop-locale")
                .contains("theme.toggle()");
        assertThat(locales)
                .contains("shop: '\\u5546\\u57ce'")
                .contains("orders: '\\u8ba2\\u5355'")
                .contains("initialLocale()")
                .doesNotContain("鍟嗗煄")
                .doesNotContain("鐧诲綍");
        assertThat(theme)
                .contains("storageKey = 'monkeyshop-theme'")
                .contains("darkClass = 'dark'")
                .contains("document.documentElement.classList.toggle(darkClass, dark)")
                .contains("localStorage.setItem(storageKey, dark ? 'dark' : 'light')");
        assertThat(a11y)
                .contains("app shell toggles language and dark theme")
                .contains("getByRole('link', { name: '商城', exact: true })")
                .contains("toHaveClass(/dark/)");
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
    void viteImageFallbackJsonLdAndSsrReservationStayCspReady() throws IOException {
        String productImage =
                Files.readString(Path.of("frontend/src/components/ProductImage.vue"), StandardCharsets.UTF_8);
        String shop = Files.readString(Path.of("frontend/src/views/ShopView.vue"), StandardCharsets.UTF_8);
        String productDetail =
                Files.readString(Path.of("frontend/src/views/ProductDetailView.vue"), StandardCharsets.UTF_8);
        String productJsonLd = Files.readString(Path.of("frontend/src/seo/product-json-ld.ts"), StandardCharsets.UTF_8);
        String useJsonLd = Files.readString(Path.of("frontend/src/seo/useJsonLd.ts"), StandardCharsets.UTF_8);
        String ssrReservation =
                Files.readString(Path.of("frontend/src/seo/nuxt-reservation.ts"), StandardCharsets.UTF_8);
        String robots = Files.readString(Path.of("frontend/public/robots.txt"), StandardCharsets.UTF_8);
        String sitemap = Files.readString(Path.of("frontend/public/sitemap.xml"), StandardCharsets.UTF_8);

        assertThat(productImage)
                .contains("v-fallback-img")
                .contains("addEventListener('error'")
                .doesNotContain("@error=");
        assertThat(shop)
                .contains("productListJsonLd")
                .contains("productListStructuredData")
                .contains("useJsonLd('monkeyshop-product-list-jsonld'")
                .contains("`/shop/${monkey.id}`");
        assertThat(productDetail)
                .contains("useJsonLd('monkeyshop-product-jsonld'")
                .contains("productJsonLd(checkoutProduct.value)")
                .contains("selectedSkuId: selectedSku.value?.id")
                .contains("useCheckout()")
                .contains("listMonkeys()");
        assertThat(productJsonLd)
                .contains("'@context': 'https://schema.org'")
                .contains("'@type': 'Product'")
                .contains("'@type': 'ItemList'")
                .contains("priceCurrency")
                .contains("availability")
                .contains("url: `${siteOrigin}/shop/${monkey.id}`");
        assertThat(useJsonLd)
                .contains("application/ld+json")
                .contains("meta[name=\"csp-nonce\"]")
                .contains("script.setAttribute('nonce', nonce)")
                .contains("serializeJsonLd")
                .contains("data.value == null");
        assertThat(ssrReservation)
                .contains("nuxtSsrRouteRules")
                .contains("path: '/shop'")
                .contains("path: '/shop/:productId'")
                .contains("rendering: 'ssr'")
                .contains("path: '/orders'")
                .contains("path: '/admin'")
                .contains("rendering: 'csr'")
                .contains("nuxtPrerenderRoutes")
                .contains("nuxtSitemapDynamicSources")
                .contains("source: '/api/v1/monkeys'");
        assertThat(robots)
                .contains("User-agent: *")
                .contains("Allow: /")
                .contains("Sitemap: https://monkeyshop.example.com/sitemap.xml");
        assertThat(sitemap)
                .contains("<loc>https://monkeyshop.example.com/shop</loc>")
                .contains("<priority>1.0</priority>");
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
        String ws5Verifier = Files.readString(Path.of("scripts/verify-ws5-frontend.ps1"), StandardCharsets.UTF_8);
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);

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
        assertThat(ws5Verifier)
                .contains("npm run audit")
                .contains("npm run format")
                .contains("npm run build")
                .contains("npm run lint")
                .contains("npm run test:api-contract")
                .contains("npm run test:a11y")
                .contains("npm run test:lighthouse")
                .contains("largest-contentful-paint")
                .contains("[double]$MinimumLighthouseScore = 0.95")
                .contains("[int]$MaximumLcpMilliseconds = 2500");
        assertThat(readme)
                .contains(".\\scripts\\verify-ws5-frontend.ps1")
                .contains("Lighthouse must keep performance, accessibility, best-practices, and SEO at or above 95")
                .contains("LCP below 2.5s");
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

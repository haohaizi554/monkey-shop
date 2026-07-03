package com.example.monkey.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Ws3MarketingWorkflowTest {

    @Test
    void marketingArtifactsWireCouponSeckillGroupBuyToSharedGuards() throws IOException {
        String docs = read("docs/marketing/ws3.md");
        String service =
                read("src/main/java/com/example/monkey/marketing/application/MarketingApplicationService.java");
        String controller = read("src/main/java/com/example/monkey/marketing/interfaces/MarketingController.java");
        String lock =
                read("src/main/java/com/example/monkey/marketing/infrastructure/RedissonMarketingLockManager.java");
        String idempotency =
                read("src/main/java/com/example/monkey/marketing/infrastructure/RedisMarketingIdempotencyStore.java");
        String rateLimit = read("src/main/java/com/example/monkey/shared/interfaces/security/ApiRateLimitFilter.java");
        String frontendApi = read("frontend/src/api/marketing.ts");
        String frontendView = read("frontend/src/views/MarketingView.vue");

        assertThat(docs)
                .contains("Coupons support threshold")
                .contains("1000 concurrent seckill attempts")
                .contains("V24")
                .contains("V25")
                .contains("V26");
        assertThat(service)
                .contains("@WithSpan(\"marketing.seckill.order\")")
                .contains("captchaService.externalProviderEnabled()")
                .contains("idGenerator.nextId()")
                .contains("AuditService.MARKETING_SECKILL_ORDERED")
                .contains("name = \"marketing-expire-group-buy-teams\"");
        assertThat(controller)
                .contains("@RequestMapping({\"/api/marketing\", \"/api/v1/marketing\"})")
                .contains("hasAuthority('ORDER_CREATE')");
        assertThat(lock)
                .contains("marketing:seckill:activity:")
                .contains("tryLock(WAIT_TIME.toMillis(), LEASE_TIME.toMillis(), TimeUnit.MILLISECONDS)");
        assertThat(idempotency).contains("marketing:idempotency:").contains("setIfAbsent");
        assertThat(rateLimit).contains("/api/seckill/internal/active").contains("ApiRateLimitOperation.SECKILL");
        assertThat(frontendApi).contains("createSeckillOrder").contains("joinGroupBuy");
        assertThat(frontendView).contains("runSeckill").contains("runJoinGroup");
    }

    @Test
    void migrationsDefineCouponSeckillAndGroupBuySchema() throws IOException {
        String coupon = read("src/main/resources/db/migration/V24__marketing_coupon.sql");
        String seckill = read("src/main/resources/db/migration/V25__marketing_seckill.sql");
        String groupBuy = read("src/main/resources/db/migration/V26__marketing_group_buy.sql");

        assertThat(coupon)
                .contains("CREATE TABLE marketing_coupon")
                .contains("CREATE TABLE marketing_user_coupon")
                .contains("uk_marketing_user_coupon_user_coupon")
                .contains("fk_marketing_user_coupon_coupon");
        assertThat(seckill)
                .contains("CREATE TABLE marketing_seckill_activity")
                .contains("CREATE TABLE marketing_seckill_order")
                .contains("uk_marketing_seckill_user_idempotency")
                .contains("fk_marketing_seckill_order_activity");
        assertThat(groupBuy)
                .contains("CREATE TABLE marketing_group_buy_activity")
                .contains("CREATE TABLE marketing_group_buy_team")
                .contains("CREATE TABLE marketing_group_buy_member")
                .contains("uk_marketing_group_buy_member_user");
    }

    @Test
    void marketingDomainStaysFrameworkFree() throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> forbidden = List.of("org.springframework", "jakarta.persistence", "org.hibernate");

        try (var paths = Files.walk(Path.of("src/main/java/com/example/monkey/marketing/domain"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                for (String pattern : forbidden) {
                    if (content.contains(pattern)) {
                        violations.add(path.normalize().toString().replace('\\', '/') + " contains " + pattern);
                    }
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}

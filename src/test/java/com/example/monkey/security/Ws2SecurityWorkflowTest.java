package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Ws2SecurityWorkflowTest {

    private static final List<String> FORBIDDEN_SESSION_IDENTITY_PATTERNS = List.of(
            "HttpSession",
            "session.getAttribute",
            "session.setAttribute",
            "getSession().getAttribute",
            "getSession().setAttribute");

    @Test
    void ws2UsesCentralJwtRbacAndMethodSecurity() throws IOException {
        String securityConfig = read("src/main/java/com/example/monkey/config/SecurityConfig.java");
        String jwtService = read("src/main/java/com/example/monkey/security/JwtTokenService.java");
        String user = read("src/main/java/com/example/monkey/entity/User.java");
        String role = read("src/main/java/com/example/monkey/entity/Role.java");
        String permission = read("src/main/java/com/example/monkey/entity/Permission.java");
        String rbacMigration = read("src/main/resources/db/migration/V6__rbac_roles_permissions.sql");

        assertThat(securityConfig)
                .contains("@EnableMethodSecurity")
                .contains("JwtAuthenticationFilter")
                .contains("CookieCsrfTokenRepository.withHttpOnlyFalse()")
                .contains(".anyRequest()\n                        .denyAll()");
        assertThat(securityConfig).doesNotContain("hasRole(").doesNotContain("hasAnyRole(");

        assertThat(jwtService)
                .contains("TOKEN_TYPE_ACCESS")
                .contains("TOKEN_TYPE_REFRESH")
                .contains("rotateRefreshToken")
                .contains("revokeUserTokensForRefreshTokenReuse")
                .contains("REDIS_REFRESH_TOKEN_PREFIX")
                .contains("REDIS_REVOKED_ACCESS_PREFIX")
                .contains("REDIS_REVOKED_USER_PREFIX");
        assertThat(user)
                .contains("implements UserDetails")
                .contains("Set<Role>")
                .contains("getAuthorities()");
        assertThat(role).contains("Set<Permission>");
        assertThat(permission).contains("permission");
        assertThat(rbacMigration)
                .contains("CREATE TABLE IF NOT EXISTS `permissions`")
                .contains("CREATE TABLE IF NOT EXISTS `role_permissions`")
                .contains("CREATE TABLE IF NOT EXISTS `user_roles`")
                .contains("ORDER_MANAGE")
                .contains("PRODUCT_MANAGE");
    }

    @Test
    void ws2ControllersUsePermissionGuardsAndOwnedOrderSpel() throws IOException {
        String authorizationGuard =
                read("src/test/java/com/example/monkey/controller/ControllerAuthorizationDeclarationTest.java");
        String orderController = read("src/main/java/com/example/monkey/controller/OrderController.java");
        String orderService = read("src/main/java/com/example/monkey/service/OrderService.java");
        String orderSecurityTest = read("src/test/java/com/example/monkey/controller/OrderControllerSecurityTest.java");

        assertThat(authorizationGuard)
                .contains("ClassPathScanningCandidateComponentProvider")
                .contains("RestController.class")
                .contains("everyControllerMappingDeclaresMethodSecurityIntent")
                .contains("nonPublicControllerMappingsUsePermissionAuthorities");
        assertThat(orderController)
                .contains("@orderOwnership.isOwner(#id, authentication)")
                .contains("receiveOrder(id, requireUserId(currentUser))")
                .contains("applyReturn(id, requireUserId(currentUser))")
                .contains("shipReturn(id, requireUserId(currentUser))")
                .contains("hasAuthority('ORDER_MANAGE')");
        assertThat(orderService)
                .contains("requireOwnedOrder(Long orderId, Long userId)")
                .contains("findVisibleByIdAndUserId(orderId, userId)");
        assertThat(orderSecurityTest)
                .contains("userOrderRoutesRejectNonOwners")
                .contains("adminCannotUseUserOwnedOrderRoutes")
                .contains("adminOrderRoutesRejectUsers");
    }

    @Test
    void ws2DoesNotUseServletSessionAsIdentityStore() throws IOException {
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(Path.of("src/main/java/com/example/monkey"))) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(Ws2SecurityWorkflowTest::isJava)
                    .toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                for (String pattern : FORBIDDEN_SESSION_IDENTITY_PATTERNS) {
                    if (source.contains(pattern)) {
                        violations.add(normalized(path) + " contains " + pattern);
                    }
                }
            }
        }

        assertThat(violations).isEmpty();
    }

    @Test
    void ws2LoginResetPasswordAndMfaControlsArePresent() throws IOException {
        String authController = read("src/main/java/com/example/monkey/controller/AuthController.java");
        String loginAttempts = read("src/main/java/com/example/monkey/security/LoginAttemptService.java");
        String passwordPolicy = read("src/main/java/com/example/monkey/security/PasswordPolicy.java");
        String pwnedChecker = read("src/main/java/com/example/monkey/security/PwnedPasswordChecker.java");
        String passwordHistoryMigration = read("src/main/resources/db/migration/V4__password_history.sql");
        String totpService = read("src/main/java/com/example/monkey/security/TotpService.java");
        String dataInitializer = read("src/main/java/com/example/monkey/config/DataInitializer.java");

        assertThat(authController)
                .contains("LOGIN_BAD_CREDENTIALS = \"username or password is incorrect\"")
                .contains("LOGIN_CAPTCHA_REQUIRED")
                .contains("ADMIN_MFA_REQUIRED")
                .contains("ADMIN_MFA_INVALID")
                .contains("PASSWORD_RESET_OTP_REQUIRED")
                .contains("consumeResetChallenge")
                .contains("revokeUserTokens");
        assertThat(loginAttempts)
                .contains("Bucket")
                .contains("max-attempts-per-window:5")
                .contains("failure-lock-threshold:5")
                .contains("lock-seconds:900")
                .contains("login:lock:");
        assertThat(passwordPolicy)
                .contains("MIN_LENGTH = 10")
                .contains("LengthRule(MIN_LENGTH")
                .contains("EnglishCharacterData.LowerCase")
                .contains("EnglishCharacterData.UpperCase")
                .contains("EnglishCharacterData.Digit")
                .contains("EnglishCharacterData.Special");
        assertThat(pwnedChecker).contains("api.pwnedpasswords.com/range").contains("SHA-1");
        assertThat(passwordHistoryMigration).contains("password_history").contains("password_hash");
        assertThat(totpService).contains("HmacSHA1").contains("verify");
        assertThat(dataInitializer)
                .contains("ADMIN_TOTP_SECRET must be set")
                .contains("Existing administrator accounts must enable TOTP MFA");
    }

    @Test
    void ws2ProductionProfilesFailClosedOnMissingSharedAuthState() throws IOException {
        String staging = read("src/main/resources/application-staging.yml");
        String prod = read("src/main/resources/application-prod.yml");
        String docs = read("README.md");

        assertThat(staging)
                .contains("require-redis-state: ${APP_AUTH_REQUIRE_REDIS_STATE:true}")
                .contains("require-redis-token-store: ${APP_JWT_REQUIRE_REDIS_TOKEN_STORE:true}");
        assertThat(prod)
                .contains("require-redis-state: ${APP_AUTH_REQUIRE_REDIS_STATE:true}")
                .contains("allow-generated-secret: ${APP_JWT_ALLOW_GENERATED_SECRET:false}")
                .contains("require-redis-token-store: ${APP_JWT_REQUIRE_REDIS_TOKEN_STORE:true}");
        assertThat(docs)
                .contains("Redis-backed JWT refresh-token storage and revocation")
                .contains("login rate limits, lockouts, and captcha challenges stay shared across replicas");
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static boolean isJava(Path path) {
        return path.toString().endsWith(".java");
    }

    private static String normalized(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}

package com.example.monkey.config;

import com.example.monkey.domain.user.UserAccountStore;
import com.example.monkey.domain.user.UserAccountStore.UserAccount;
import com.example.monkey.domain.user.UserMfaVerifier;
import com.example.monkey.domain.user.UserPasswordPolicy;
import com.example.monkey.domain.user.UserRoles;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    public CommandLineRunner initData(
            UserAccountStore userAccountStore,
            PasswordEncoder passwordEncoder,
            UserPasswordPolicy passwordPolicy,
            UserMfaVerifier totpService,
            @Value("${app.admin.username:admin}") String adminUsername,
            @Value("${app.admin.password:}") String adminPassword,
            @Value("${app.admin.totp-secret:}") String adminTotpSecret) {
        return args -> {
            List<UserAccount> admins = userAccountStore.findByRole(UserRoles.ADMIN);
            if (admins != null && !admins.isEmpty()) {
                verifyExistingAdminMfa(admins, totpService);
                return;
            }
            if (userAccountStore.findByUsername(adminUsername).isPresent()) {
                throw new IllegalStateException("ADMIN_INIT_USERNAME already exists as a non-admin user");
            }
            if (!StringUtils.hasText(adminPassword)) {
                throw new IllegalStateException(
                        "ADMIN_INIT_PASSWORD must be set before bootstrapping the first administrator");
            }
            if (!StringUtils.hasText(adminTotpSecret)) {
                throw new IllegalStateException(
                        "ADMIN_TOTP_SECRET must be set before bootstrapping the first administrator");
            }
            if (!totpService.isValidSecret(adminTotpSecret)) {
                throw new IllegalStateException("ADMIN_TOTP_SECRET must be a valid Base32 secret");
            }
            UserPasswordPolicy.PasswordPolicyResult passwordResult = passwordPolicy.validate(adminPassword);
            if (!passwordResult.valid()) {
                throw new IllegalStateException(
                        "ADMIN_INIT_PASSWORD does not meet policy: " + String.join("; ", passwordResult.violations()));
            }

            LocalDateTime now = LocalDateTime.now();
            String encodedPassword = passwordEncoder.encode(adminPassword);
            UserAccount savedAdmin = userAccountStore.save(new UserAccount(
                    null,
                    adminUsername,
                    encodedPassword,
                    null,
                    null,
                    "/images/default_avatar.png",
                    UserRoles.ADMIN,
                    "Administrator",
                    now,
                    true,
                    adminTotpSecret.trim(),
                    true,
                    List.of("ROLE_ADMIN")));
            if (savedAdmin.id() != null) {
                userAccountStore.recordPasswordHistory(savedAdmin.id(), encodedPassword, now);
            }
            log.info("Initial administrator account was created from externalized configuration");
        };
    }

    private static void verifyExistingAdminMfa(List<UserAccount> admins, UserMfaVerifier totpService) {
        List<UserAccount> invalidAdmins = admins.stream()
                .filter(admin -> !hasValidAdminMfa(admin, totpService))
                .toList();
        if (!invalidAdmins.isEmpty()) {
            throw new IllegalStateException("Existing administrator accounts must enable TOTP MFA before startup");
        }
    }

    private static boolean hasValidAdminMfa(UserAccount admin, UserMfaVerifier totpService) {
        return admin != null
                && admin.mfaEnabled()
                && StringUtils.hasText(admin.totpSecret())
                && totpService.isValidSecret(admin.totpSecret().trim());
    }
}

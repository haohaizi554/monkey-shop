package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.domain.UserMfaVerifier;
import com.example.monkey.user.domain.UserPasswordHasher;
import com.example.monkey.user.domain.UserPasswordPolicy;
import com.example.monkey.user.domain.UserPasswordPolicy.PasswordPolicyMetadata;
import com.example.monkey.user.domain.UserRoles;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock
    private UserAccountStore userAccountStore;

    @Mock
    private UserPasswordHasher passwordHasher;

    @Mock
    private UserMfaVerifier totpService;

    private DataInitializer dataInitializer;

    @BeforeEach
    void setUp() {
        dataInitializer = new DataInitializer();
    }

    @Test
    void refusesToBootstrapAdminWhenPasswordIsMissing() throws Exception {
        when(userAccountStore.findByRole(UserRoles.ADMIN)).thenReturn(List.of());
        CommandLineRunner runner = dataInitializer.initData(
                userAccountStore, passwordHasher, testPasswordPolicy(), totpService, "admin", "", "");

        assertThatThrownBy(() -> runner.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_INIT_PASSWORD must be set");
        verify(userAccountStore, never()).save(any(UserAccount.class));
    }

    @Test
    void refusesToBootstrapAdminWhenPasswordIsWeak() throws Exception {
        when(userAccountStore.findByRole(UserRoles.ADMIN)).thenReturn(List.of());
        when(totpService.isValidSecret("JBSWY3DPEHPK3PXP")).thenReturn(true);
        CommandLineRunner runner = dataInitializer.initData(
                userAccountStore,
                passwordHasher,
                testPasswordPolicy(),
                totpService,
                "admin",
                "Password1",
                "JBSWY3DPEHPK3PXP");

        assertThatThrownBy(() -> runner.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not meet policy");
        verify(userAccountStore, never()).save(any(UserAccount.class));
    }

    @Test
    void refusesToBootstrapAdminWhenTotpSecretIsMissing() throws Exception {
        when(userAccountStore.findByRole(UserRoles.ADMIN)).thenReturn(List.of());
        CommandLineRunner runner = dataInitializer.initData(
                userAccountStore, passwordHasher, testPasswordPolicy(), totpService, "admin", "StrongPass1!", "");

        assertThatThrownBy(() -> runner.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_TOTP_SECRET must be set");
        verify(userAccountStore, never()).save(any(UserAccount.class));
    }

    @Test
    void refusesToBootstrapAdminWhenUsernameAlreadyBelongsToUser() throws Exception {
        when(userAccountStore.findByRole(UserRoles.ADMIN)).thenReturn(List.of());
        when(userAccountStore.findByUsername("admin")).thenReturn(Optional.of(account("admin", false, "")));
        CommandLineRunner runner = dataInitializer.initData(
                userAccountStore,
                passwordHasher,
                testPasswordPolicy(),
                totpService,
                "admin",
                "StrongPass1!",
                "JBSWY3DPEHPK3PXP");

        assertThatThrownBy(() -> runner.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_INIT_USERNAME already exists");
        verify(userAccountStore, never()).save(any(UserAccount.class));
    }

    @Test
    void refusesStartupWhenExistingAdminHasNoValidTotpMfa() throws Exception {
        when(userAccountStore.findByRole(UserRoles.ADMIN)).thenReturn(List.of(account("admin", false, "")));
        CommandLineRunner runner = dataInitializer.initData(
                userAccountStore,
                passwordHasher,
                testPasswordPolicy(),
                totpService,
                "admin",
                "StrongPass1!",
                "JBSWY3DPEHPK3PXP");

        assertThatThrownBy(() -> runner.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Existing administrator accounts must enable TOTP MFA");
        verify(userAccountStore, never()).save(any(UserAccount.class));
    }

    @Test
    void acceptsExistingAdminOnlyWhenTotpMfaIsValid() throws Exception {
        when(userAccountStore.findByRole(UserRoles.ADMIN))
                .thenReturn(List.of(account("admin", true, "JBSWY3DPEHPK3PXP")));
        when(totpService.isValidSecret("JBSWY3DPEHPK3PXP")).thenReturn(true);
        CommandLineRunner runner = dataInitializer.initData(
                userAccountStore,
                passwordHasher,
                testPasswordPolicy(),
                totpService,
                "admin",
                "StrongPass1!",
                "JBSWY3DPEHPK3PXP");

        runner.run();

        verify(userAccountStore, never()).save(any(UserAccount.class));
        verify(userAccountStore, never()).recordPasswordHistory(any(), any(), any());
    }

    @Test
    void bootstrapsAdminWithoutAnUnfulfillableForcedPasswordChange() throws Exception {
        when(userAccountStore.findByRole(UserRoles.ADMIN)).thenReturn(List.of());
        when(totpService.isValidSecret("JBSWY3DPEHPK3PXP")).thenReturn(true);
        when(passwordHasher.hash("StrongPass1!")).thenReturn("encoded-password");
        when(userAccountStore.save(any(UserAccount.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));
        CommandLineRunner runner = dataInitializer.initData(
                userAccountStore,
                passwordHasher,
                testPasswordPolicy(),
                totpService,
                "root-admin",
                "StrongPass1!",
                "JBSWY3DPEHPK3PXP");

        runner.run();

        ArgumentCaptor<UserAccount> adminCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountStore).save(adminCaptor.capture());
        UserAccount admin = adminCaptor.getValue();
        assertThat(admin.id()).isNull();
        assertThat(admin.username()).isEqualTo("root-admin");
        assertThat(admin.passwordHash()).isEqualTo("encoded-password");
        assertThat(admin.nickname()).isEqualTo("Administrator");
        assertThat(admin.role()).isEqualTo(UserRoles.ADMIN);
        assertThat(admin.authorityNames()).containsExactly("ROLE_ADMIN");
        assertThat(admin.totpSecret()).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(admin.mfaEnabled()).isTrue();
        assertThat(admin.passwordLastChangedAt()).isNotNull();
        assertThat(admin.passwordChangeRequired()).isFalse();
        verify(userAccountStore).recordPasswordHistory(1L, "encoded-password", admin.passwordLastChangedAt());
    }

    private static UserPasswordPolicy testPasswordPolicy() {
        return new UserPasswordPolicy() {
            @Override
            public PasswordPolicyMetadata metadata() {
                return new PasswordPolicyMetadata(10, true, true, true, true, true);
            }

            @Override
            public PasswordPolicyResult validate(String password) {
                if ("Password1".equals(password)) {
                    return new PasswordPolicyResult(false, List.of("password does not meet policy"));
                }
                return new PasswordPolicyResult(true, List.of());
            }

            @Override
            public void validateOrThrow(String password) {
                PasswordPolicyResult result = validate(password);
                if (!result.valid()) {
                    throw new IllegalStateException(String.join("; ", result.violations()));
                }
            }
        };
    }

    private static UserAccount account(String username, boolean mfaEnabled, String totpSecret) {
        return new UserAccount(
                1L,
                username,
                "encoded-password",
                null,
                null,
                "/images/default_avatar.png",
                UserRoles.ADMIN,
                "Administrator",
                LocalDateTime.parse("2026-06-29T12:00:00"),
                false,
                totpSecret,
                mfaEnabled,
                List.of("ROLE_ADMIN"));
    }

    private static UserAccount withId(UserAccount account, Long id) {
        return new UserAccount(
                id,
                account.username(),
                account.passwordHash(),
                account.phone(),
                account.email(),
                account.avatar(),
                account.role(),
                account.nickname(),
                account.passwordLastChangedAt(),
                account.passwordChangeRequired(),
                account.totpSecret(),
                account.mfaEnabled(),
                account.authorityNames());
    }
}

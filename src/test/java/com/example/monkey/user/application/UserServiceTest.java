package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.storage.ImageCleanupService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.user.application.dto.UserProfileResponseDto;
import com.example.monkey.user.domain.AuthPrincipal;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.domain.UserMfaVerifier;
import com.example.monkey.user.domain.UserPasswordHasher;
import com.example.monkey.user.domain.UserPasswordPolicy;
import com.example.monkey.user.domain.UserRoles;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserAccountStore userAccountStore;

    @Mock
    private UserPasswordHasher passwordHasher;

    @Mock
    private ImageCleanupService imageCleanupService;

    @Mock
    private ImageReferenceService imageReferenceService;

    @Mock
    private UserMfaVerifier totpService;

    private UserService userService;
    private static final String MISSING_ACCOUNT_PASSWORD_HASH = "missing-account-hash";

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userAccountStore,
                passwordHasher,
                imageCleanupService,
                imageReferenceService,
                testPasswordPolicy(),
                totpService,
                MISSING_ACCOUNT_PASSWORD_HASH);
    }

    @Test
    void readOperationsDeclareReadOnlyTransactions() throws NoSuchMethodException {
        assertReadOnlyTransaction("authenticate", String.class, String.class);
        assertReadOnlyTransaction("currentPrincipal", Long.class);
        assertReadOnlyTransaction(
                "getUserInfo", com.example.monkey.shared.application.security.SessionUser.class, boolean.class);
        assertReadOnlyTransaction("passwordResetTargetMatches", String.class, String.class);
        assertReadOnlyTransaction("passwordResetTargetMatches", String.class, String.class, String.class);
        assertReadOnlyTransaction("findUserIdByUsername", String.class);
        assertReadOnlyTransaction("verifyAdminTotp", Long.class, String.class);
    }

    @Test
    void avatarUpdateDeclaresWriteTransaction() throws NoSuchMethodException {
        Transactional transaction = transactionOn("updateAvatar", Long.class, String.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isFalse();
    }

    @Test
    void registerRejectsWeakPasswordBeforeEncodingOrSaving() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> userService.register("alice", "Password1", "18888888888", null))
                .withMessageStartingWith("password policy violation")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(passwordHasher, never()).hash(any());
        verify(userAccountStore, never()).save(any(UserAccount.class));
    }

    @Test
    void registerReturnsGenericFailureWhenUsernameAlreadyExists() {
        when(userAccountStore.findByUsername("alice")).thenReturn(Optional.of(account()));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> userService.register("alice", "StrongPass1!", "18888888888", null))
                .withMessage("registration failed")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(passwordHasher, never()).hash(any());
        verify(userAccountStore, never()).save(any(UserAccount.class));
    }

    @Test
    void registerEncodesStrongPasswordAndUsesDefaultAvatar() {
        when(passwordHasher.hash("StrongPass1!")).thenReturn("encoded-password");
        when(userAccountStore.save(any(UserAccount.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 7L));

        userService.register("alice", "StrongPass1!", "18888888888", null);

        UserAccount user = captureSavedAccount();
        assertThat(user.username()).isEqualTo("alice");
        assertThat(user.passwordHash()).isEqualTo("encoded-password");
        assertThat(user.phone()).isEqualTo("18888888888");
        assertThat(user.email()).isNull();
        assertThat(user.avatar()).isEqualTo("/images/default_avatar.png");
        assertThat(user.role()).isEqualTo(UserRoles.USER);
        assertThat(user.passwordLastChangedAt()).isNotNull();
        verify(userAccountStore).recordPasswordHistory(any(), any(), any(LocalDateTime.class));
        verify(userAccountStore).recordPasswordHistory(7L, "encoded-password", user.passwordLastChangedAt());
        verify(imageReferenceService).retain("/images/default_avatar.png");
    }

    @Test
    void registerReturnsGenericFailureWhenSaveFails() {
        when(passwordHasher.hash("StrongPass1!")).thenReturn("encoded-password");
        when(userAccountStore.save(any(UserAccount.class))).thenThrow(new RuntimeException("database unavailable"));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> userService.register("alice", "StrongPass1!", "18888888888", null))
                .withMessage("registration failed")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(userAccountStore, never()).recordPasswordHistory(any(), any(), any());
    }

    @Test
    void registerStoresOptionalEmailForResetChannel() {
        when(passwordHasher.hash("StrongPass1!")).thenReturn("encoded-password");

        userService.register("alice", "StrongPass1!", "18888888888", " alice@example.com ", "/images/custom.png");

        UserAccount user = captureSavedAccount();
        assertThat(user.email()).isEqualTo("alice@example.com");
        assertThat(user.avatar()).isEqualTo("/images/custom.png");
        verify(imageReferenceService).retain("/images/custom.png");
    }

    @Test
    void failedAuthenticationReturnsNull() {
        when(userAccountStore.findByUsername("alice")).thenReturn(Optional.of(account()));
        when(passwordHasher.matches("wrong-password", "encoded-password")).thenReturn(false);

        AuthPrincipal result = userService.authenticate("alice", "wrong-password");

        assertThat(result).isNull();
    }

    @Test
    void missingAccountStillRunsPasswordVerification() {
        AuthPrincipal result = userService.authenticate("missing", "guess");

        assertThat(result).isNull();
        verify(userAccountStore).findByUsername("missing");
        verify(passwordHasher).matches("guess", MISSING_ACCOUNT_PASSWORD_HASH);
    }

    @Test
    void successfulAuthenticationReturnsUserPrincipalAfterPasswordMatch() {
        UserAccount user = accountWithChangeRequired(true);
        when(userAccountStore.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("StrongPass1!", "encoded-password")).thenReturn(true);

        AuthPrincipal result = userService.authenticate("alice", "StrongPass1!");

        assertThat(result).isEqualTo(new AuthPrincipal(7L, UserRoles.USER, List.of(), true));
    }

    @Test
    void expiredPasswordAuthenticatesWithPasswordChangeRequired() {
        UserAccount user = accountWithLastChanged(LocalDateTime.now().minusDays(91));
        when(userAccountStore.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordHasher.matches("StrongPass1!", "encoded-password")).thenReturn(true);

        AuthPrincipal result = userService.authenticate("alice", "StrongPass1!");

        assertThat(result).isEqualTo(new AuthPrincipal(7L, UserRoles.USER, List.of(), true));
    }

    @Test
    void currentPrincipalReturnsFreshAuthoritiesForEligibleUser() {
        UserAccount user = accountWithAuthorities(List.of("ORDER_READ_OWN"));
        when(userAccountStore.findById(7L)).thenReturn(Optional.of(user));

        Optional<AuthPrincipal> result = userService.currentPrincipal(7L);

        assertThat(result).contains(new AuthPrincipal(7L, UserRoles.USER, List.of("ORDER_READ_OWN")));
    }

    @Test
    void currentPrincipalMarksExpiredCredentialsAsPasswordChangeRequired() {
        UserAccount user = accountWithLastChanged(LocalDateTime.now().minusDays(91));
        when(userAccountStore.findById(7L)).thenReturn(Optional.of(user));

        Optional<AuthPrincipal> result = userService.currentPrincipal(7L);

        assertThat(result).contains(new AuthPrincipal(7L, UserRoles.USER, List.of(), true));
    }

    @Test
    void adminLoginUsesUnifiedUserRoleBeforeLegacyAdminTable() {
        UserAccount admin = accountWithRole(UserRoles.ADMIN);
        when(userAccountStore.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordHasher.matches("StrongPass1!", "encoded-password")).thenReturn(true);

        AuthPrincipal result = userService.authenticate("admin", "StrongPass1!");

        assertThat(result).isEqualTo(new AuthPrincipal(7L, UserRoles.ADMIN));
        verify(userAccountStore).findByUsername("admin");
    }

    @Test
    void legacyAdminTableIsNotUsedForRuntimeAuthenticationAfterMigration() {
        AuthPrincipal result = userService.authenticate("admin", "StrongPass1!");

        assertThat(result).isNull();
        verify(userAccountStore).findByUsername("admin");
        verify(passwordHasher).matches("StrongPass1!", MISSING_ACCOUNT_PASSWORD_HASH);
    }

    @Test
    void adminProfileReadsUnifiedUserNickname() {
        UserAccount admin = accountWithProfile(UserRoles.ADMIN, "Operations", "/images/default_avatar.png");
        when(userAccountStore.findById(1L)).thenReturn(Optional.of(withId(admin, 1L)));

        UserProfileResponseDto info = userService.getUserInfo(
                new com.example.monkey.shared.application.security.SessionUser(1L, "ADMIN"), true);

        assertThat(info.isLogin()).isTrue();
        assertThat(info.identity()).isEqualTo("ADMIN");
        assertThat(info.username()).isEqualTo("Operations");
        assertThat(info.maskedPhone()).isEqualTo("admin account");
        assertThat(info.passwordChangeRequired()).isFalse();
    }

    @Test
    void userProfileFallsBackToAnonymousWhenCurrentUserIsMissing() {
        when(userAccountStore.findById(7L)).thenReturn(Optional.empty());

        UserProfileResponseDto info = userService.getUserInfo(
                new com.example.monkey.shared.application.security.SessionUser(7L, "USER"), true);

        assertThat(info.isLogin()).isFalse();
        assertThat(info.identity()).isNull();
    }

    @Test
    void userProfileIncludesDefaultAvatarWhenUserAvatarIsMissing() {
        UserAccount user = accountWithProfile(UserRoles.USER, null, null);
        when(userAccountStore.findById(7L)).thenReturn(Optional.of(user));

        UserProfileResponseDto info = userService.getUserInfo(
                new com.example.monkey.shared.application.security.SessionUser(7L, "USER"), true);

        assertThat(info.isLogin()).isTrue();
        assertThat(info.identity()).isEqualTo("USER");
        assertThat(info.username()).isEqualTo("alice");
        assertThat(info.avatar()).isEqualTo("/images/default_avatar.png");
        assertThat(info.maskedPhone()).contains("8888");
    }

    @Test
    void adminProfileFallsBackToAnonymousWhenStoredUserIsNotAdmin() {
        when(userAccountStore.findById(1L)).thenReturn(Optional.of(withId(account(), 1L)));

        UserProfileResponseDto info = userService.getUserInfo(
                new com.example.monkey.shared.application.security.SessionUser(1L, "ADMIN"), true);

        assertThat(info.isLogin()).isFalse();
    }

    @Test
    void adminTotpVerificationRequiresAdminWithEnabledMfaAndValidCode() {
        UserAccount admin = accountWithMfa(UserRoles.ADMIN, true, "JBSWY3DPEHPK3PXP");
        when(userAccountStore.findById(1L)).thenReturn(Optional.of(withId(admin, 1L)));
        when(totpService.verifyCode("JBSWY3DPEHPK3PXP", "654321")).thenReturn(true);

        assertThat(userService.verifyAdminTotp(1L, "654321")).isTrue();
    }

    @Test
    void adminTotpVerificationRejectsAdminWithoutEnabledMfa() {
        UserAccount admin = accountWithMfa(UserRoles.ADMIN, false, "JBSWY3DPEHPK3PXP");
        when(userAccountStore.findById(1L)).thenReturn(Optional.of(withId(admin, 1L)));

        assertThat(userService.verifyAdminTotp(1L, "654321")).isFalse();
        verify(totpService, never()).verifyCode(any(), any());
    }

    @Test
    void adminTotpVerificationRejectsNonAdmin() {
        UserAccount user = accountWithMfa(UserRoles.USER, true, "JBSWY3DPEHPK3PXP");
        when(userAccountStore.findById(7L)).thenReturn(Optional.of(user));

        assertThat(userService.verifyAdminTotp(7L, "654321")).isFalse();
        verify(totpService, never()).verifyCode(any(), any());
    }

    @Test
    void passwordResetTargetCanRequireMatchingEmail() {
        when(userAccountStore.findByUsername("alice")).thenReturn(Optional.of(account()));

        assertThat(userService.passwordResetTargetMatches("alice", "18888888888", "ALICE@example.com"))
                .isTrue();
        assertThat(userService.passwordResetTargetMatches("alice", "18888888888", "other@example.com"))
                .isFalse();
    }

    @Test
    void passwordResetTargetRejectsMissingUserOrPhoneMismatch() {
        when(userAccountStore.findByUsername("alice")).thenReturn(Optional.of(account()));
        when(userAccountStore.findByUsername("missing")).thenReturn(Optional.empty());

        assertThat(userService.passwordResetTargetMatches("missing", "18888888888"))
                .isFalse();
        assertThat(userService.passwordResetTargetMatches("alice", "19999999999"))
                .isFalse();
        assertThat(userService.passwordResetTargetMatches("alice", "18888888888"))
                .isTrue();
    }

    @Test
    void findUserIdByUsernameHandlesBlankMissingAndPresentUsers() {
        when(userAccountStore.findByUsername("alice")).thenReturn(Optional.of(account()));
        when(userAccountStore.findByUsername("missing")).thenReturn(Optional.empty());

        assertThat(userService.findUserIdByUsername(" ")).isEmpty();
        assertThat(userService.findUserIdByUsername("missing")).isEmpty();
        assertThat(userService.findUserIdByUsername("alice")).contains(7L);
    }

    @Test
    void updateAvatarRetainsNewImageAndReleasesOldImage() {
        UserAccount user = accountWithAvatar("/images/old.png");
        when(userAccountStore.findById(7L)).thenReturn(Optional.of(user));

        userService.updateAvatar(7L, "/images/new.png");

        assertThat(captureSavedAccount().avatar()).isEqualTo("/images/new.png");
        verify(imageReferenceService).retain("/images/new.png");
        verify(imageReferenceService).release("/images/old.png");
        verify(imageCleanupService).tryDelete("/images/old.png");
    }

    @Test
    void updateAvatarSkipsReferenceChangesWhenPathIsUnchanged() {
        UserAccount user = accountWithAvatar("/images/avatar.png");
        when(userAccountStore.findById(7L)).thenReturn(Optional.of(user));

        userService.updateAvatar(7L, "/images/avatar.png");

        assertThat(captureSavedAccount().avatar()).isEqualTo("/images/avatar.png");
        verify(imageReferenceService, never()).retain(any());
        verify(imageReferenceService, never()).release(any());
        verify(imageCleanupService, never()).tryDelete(any());
    }

    @Test
    void updateAvatarRejectsMissingUser() {
        when(userAccountStore.findById(7L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> userService.updateAvatar(7L, "/images/new.png"))
                .withMessage("user not found")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void updatePasswordRejectsWeakPasswordBeforeSaving() {
        when(userAccountStore.findById(7L)).thenReturn(Optional.of(account()));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> userService.updatePassword(7L, "18888888888", "Password1"))
                .withMessageStartingWith("password policy violation")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(userAccountStore, never()).save(any(UserAccount.class));
    }

    @Test
    void updatePasswordRejectsMissingUserAndPhoneMismatch() {
        when(userAccountStore.findById(7L)).thenReturn(Optional.of(account()));
        when(userAccountStore.findById(8L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> userService.updatePassword(8L, "18888888888", "StrongPass1!"))
                .withMessage("user not found")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> userService.updatePassword(7L, "19999999999", "StrongPass1!"))
                .withMessage("phone verification failed")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void updatePasswordEncodesStrongPasswordAfterPhoneMatch() {
        when(userAccountStore.findById(7L)).thenReturn(Optional.of(accountWithChangeRequired(true)));
        when(passwordHasher.hash("StrongPass1!")).thenReturn("encoded-password-new");

        userService.updatePassword(7L, "18888888888", "StrongPass1!");

        UserAccount saved = captureSavedAccount();
        assertThat(saved.passwordHash()).isEqualTo("encoded-password-new");
        assertThat(saved.passwordLastChangedAt()).isNotNull();
        assertThat(saved.passwordChangeRequired()).isFalse();
        verify(userAccountStore).recordPasswordHistory(any(), any(), any(LocalDateTime.class));
    }

    @Test
    void resetPasswordAfterOtpUpdatesUserFoundByUsername() {
        when(userAccountStore.findByUsername("alice")).thenReturn(Optional.of(account()));
        when(passwordHasher.hash("StrongPass1!")).thenReturn("encoded-password-new");

        userService.resetPasswordAfterOtp("alice", "18888888888", "StrongPass1!");

        assertThat(captureSavedAccount().passwordHash()).isEqualTo("encoded-password-new");
        verify(userAccountStore).recordPasswordHistory(any(), any(), any(LocalDateTime.class));
    }

    @Test
    void updatePasswordRejectsRecentlyUsedPassword() {
        when(userAccountStore.findById(7L)).thenReturn(Optional.of(account()));
        when(userAccountStore.findRecentPasswordHashes(7L)).thenReturn(List.of("previous-hash"));
        when(passwordHasher.matches("StrongPass1!", "encoded-password")).thenReturn(false);
        when(passwordHasher.matches("StrongPass1!", "previous-hash")).thenReturn(true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> userService.updatePassword(7L, "18888888888", "StrongPass1!"))
                .withMessage("password was used recently")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(userAccountStore, never()).save(any(UserAccount.class));
        verify(userAccountStore, never()).recordPasswordHistory(any(), any(), any());
    }

    private static void assertReadOnlyTransaction(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Transactional transaction = transactionOn(methodName, parameterTypes);

        assertThat(transaction).isNotNull();
        assertThat(transaction.readOnly()).isTrue();
    }

    private static Transactional transactionOn(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = UserService.class.getMethod(methodName, parameterTypes);
        return method.getAnnotation(Transactional.class);
    }

    private static UserPasswordPolicy testPasswordPolicy() {
        return new UserPasswordPolicy() {
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
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "password policy violation: " + String.join("; ", result.violations()));
                }
            }
        };
    }

    private UserAccount captureSavedAccount() {
        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountStore).save(captor.capture());
        return captor.getValue();
    }

    private static UserAccount account() {
        return accountWith(
                7L,
                "alice",
                "encoded-password",
                "18888888888",
                "alice@example.com",
                "/avatar.png",
                UserRoles.USER,
                null,
                LocalDateTime.now().minusDays(10),
                false,
                null,
                false,
                List.of());
    }

    private static UserAccount accountWithLastChanged(LocalDateTime passwordLastChangedAt) {
        return accountWith(
                7L,
                "alice",
                "encoded-password",
                "18888888888",
                "alice@example.com",
                "/avatar.png",
                UserRoles.USER,
                null,
                passwordLastChangedAt,
                false,
                null,
                false,
                List.of());
    }

    private static UserAccount accountWithChangeRequired(boolean passwordChangeRequired) {
        UserAccount account = account();
        return accountWith(
                account.id(),
                account.username(),
                account.passwordHash(),
                account.phone(),
                account.email(),
                account.avatar(),
                account.role(),
                account.nickname(),
                account.passwordLastChangedAt(),
                passwordChangeRequired,
                account.totpSecret(),
                account.mfaEnabled(),
                account.authorityNames());
    }

    private static UserAccount accountWithAuthorities(List<String> authorityNames) {
        UserAccount account = account();
        return accountWith(
                account.id(),
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
                authorityNames);
    }

    private static UserAccount accountWithRole(String role) {
        UserAccount account = account();
        return accountWith(
                account.id(),
                account.username(),
                account.passwordHash(),
                account.phone(),
                account.email(),
                account.avatar(),
                role,
                account.nickname(),
                account.passwordLastChangedAt(),
                account.passwordChangeRequired(),
                account.totpSecret(),
                account.mfaEnabled(),
                account.authorityNames());
    }

    private static UserAccount accountWithProfile(String role, String nickname, String avatar) {
        UserAccount account = accountWithRole(role);
        return accountWith(
                account.id(),
                account.username(),
                account.passwordHash(),
                account.phone(),
                account.email(),
                avatar,
                account.role(),
                nickname,
                account.passwordLastChangedAt(),
                account.passwordChangeRequired(),
                account.totpSecret(),
                account.mfaEnabled(),
                account.authorityNames());
    }

    private static UserAccount accountWithMfa(String role, boolean enabled, String secret) {
        UserAccount account = accountWithRole(role);
        return accountWith(
                account.id(),
                account.username(),
                account.passwordHash(),
                account.phone(),
                account.email(),
                account.avatar(),
                account.role(),
                account.nickname(),
                account.passwordLastChangedAt(),
                account.passwordChangeRequired(),
                secret,
                enabled,
                account.authorityNames());
    }

    private static UserAccount accountWithAvatar(String avatar) {
        UserAccount account = account();
        return accountWith(
                account.id(),
                account.username(),
                account.passwordHash(),
                account.phone(),
                account.email(),
                avatar,
                account.role(),
                account.nickname(),
                account.passwordLastChangedAt(),
                account.passwordChangeRequired(),
                account.totpSecret(),
                account.mfaEnabled(),
                account.authorityNames());
    }

    private static UserAccount withId(UserAccount account, Long id) {
        return accountWith(
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

    private static UserAccount accountWith(
            Long id,
            String username,
            String passwordHash,
            String phone,
            String email,
            String avatar,
            String role,
            String nickname,
            LocalDateTime passwordLastChangedAt,
            boolean passwordChangeRequired,
            String totpSecret,
            boolean mfaEnabled,
            List<String> authorityNames) {
        return new UserAccount(
                id,
                username,
                passwordHash,
                phone,
                email,
                avatar,
                role,
                nickname,
                passwordLastChangedAt,
                passwordChangeRequired,
                totpSecret,
                mfaEnabled,
                authorityNames);
    }
}

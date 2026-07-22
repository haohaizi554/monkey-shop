package com.example.monkey.user.application;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.storage.ImageCleanupService;
import com.example.monkey.shared.application.tenant.TenantContext;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String DEFAULT_AVATAR = "/images/default_avatar.png";
    private static final String REGISTRATION_FAILED = "registration failed";
    private static final int PASSWORD_EXPIRATION_DAYS = 90;

    private final UserAccountStore userAccountStore;
    private final UserPasswordHasher passwordHasher;
    private final ImageCleanupService imageCleanupService;
    private final ImageReferenceService imageReferenceService;
    private final UserPasswordPolicy passwordPolicy;
    private final UserMfaVerifier totpService;
    private final String missingAccountPasswordHash;

    @Autowired
    public UserService(
            UserAccountStore userAccountStore,
            UserPasswordHasher passwordHasher,
            ImageCleanupService imageCleanupService,
            ImageReferenceService imageReferenceService,
            UserPasswordPolicy passwordPolicy,
            UserMfaVerifier totpService) {
        this(
                userAccountStore,
                passwordHasher,
                imageCleanupService,
                imageReferenceService,
                passwordPolicy,
                totpService,
                passwordHasher.hash(UUID.randomUUID().toString()));
    }

    UserService(
            UserAccountStore userAccountStore,
            UserPasswordHasher passwordHasher,
            ImageCleanupService imageCleanupService,
            ImageReferenceService imageReferenceService,
            UserPasswordPolicy passwordPolicy,
            UserMfaVerifier totpService,
            String missingAccountPasswordHash) {
        this.userAccountStore = userAccountStore;
        this.passwordHasher = passwordHasher;
        this.imageCleanupService = imageCleanupService;
        this.imageReferenceService = imageReferenceService;
        this.passwordPolicy = passwordPolicy;
        this.totpService = totpService;
        this.missingAccountPasswordHash = missingAccountPasswordHash;
    }

    @Transactional
    public void register(String username, String password, String phone, String avatarPath) {
        register(username, password, phone, null, avatarPath);
    }

    @Transactional
    public void register(String username, String password, String phone, String email, String avatarPath) {
        passwordPolicy.validateOrThrow(password);
        if (userAccountStore.findByUsername(username).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, REGISTRATION_FAILED);
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            String encodedPassword = passwordHasher.hash(password);
            UserAccount user = new UserAccount(
                    null,
                    username,
                    encodedPassword,
                    phone,
                    normalizeOptional(email),
                    avatarPath != null ? avatarPath : DEFAULT_AVATAR,
                    UserRoles.USER,
                    null,
                    now,
                    false,
                    null,
                    false,
                    List.of());
            UserAccount savedUser = userAccountStore.save(user);
            imageReferenceService.retain(user.avatar());
            recordPasswordHistory(savedUser != null ? savedUser.id() : user.id(), encodedPassword, now);
        } catch (Exception e) {
            log.warn("User registration failed");
            log.debug("User registration failure details", e);
            throw new BusinessException(ErrorCode.CONFLICT, REGISTRATION_FAILED);
        }
    }

    @Transactional(readOnly = true)
    public AuthPrincipal authenticate(String username, String rawPassword) {
        Optional<UserAccount> account = userAccountStore.findByUsername(username);
        String passwordToCheck = rawPassword == null ? "" : rawPassword;

        if (account.isPresent()
                && passwordHasher.matches(passwordToCheck, account.get().passwordHash())) {
            return principalFor(account.get());
        }
        if (account.isPresent()) {
            return null;
        }
        passwordHasher.matches(passwordToCheck, missingAccountPasswordHash);
        return null;
    }

    @Transactional(readOnly = true)
    public Optional<AuthPrincipal> currentPrincipal(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return userAccountStore.findById(userId).map(UserService::principalFor);
    }

    @Transactional(readOnly = true)
    public Optional<AuthPrincipal> currentPrincipal(Long userId, Long tenantId) {
        Optional<Long> previousTenant = TenantContext.currentTenantId();
        TenantContext.setTenantId(tenantId);
        try {
            return currentPrincipal(userId);
        } finally {
            previousTenant.ifPresentOrElse(TenantContext::setTenantId, TenantContext::clear);
        }
    }

    @Transactional(readOnly = true)
    public UserProfileResponseDto getUserInfo(SessionUser currentUser, boolean details) {
        if (currentUser == null) {
            return UserDtoAssembler.anonymousProfile();
        }

        if (UserRoles.ADMIN.equals(currentUser.role())) {
            UserAccount adminUser = userAccountStore.findById(currentUser.id()).orElse(null);
            if (adminUser != null && UserRoles.ADMIN.equals(normalizeRole(adminUser.role()))) {
                return UserDtoAssembler.adminProfile(adminUser, currentUser.role(), DEFAULT_AVATAR, details);
            }
            return UserDtoAssembler.anonymousProfile();
        }

        UserAccount user = userAccountStore.findById(currentUser.id()).orElse(null);
        if (user == null) {
            return UserDtoAssembler.anonymousProfile();
        }
        return UserDtoAssembler.userProfile(user, currentUser.role(), DEFAULT_AVATAR, details);
    }

    @Transactional
    public void updateAvatar(Long userId, String newAvatarPath) {
        UserAccount user = userAccountStore.findById(userId).orElse(null);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "user not found");
        }
        String oldAvatar = user.avatar();
        userAccountStore.save(user.withAvatar(newAvatarPath));
        if (oldAvatar != null && !oldAvatar.equals(newAvatarPath)) {
            imageReferenceService.retain(newAvatarPath);
            imageReferenceService.release(oldAvatar);
            imageCleanupService.tryDelete(oldAvatar);
        }
    }

    @Transactional
    public void updatePassword(Long userId, String phone, String newPassword) {
        UserAccount user = userAccountStore.findById(userId).orElse(null);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "user not found");
        }
        updatePasswordForUser(user, userId, phone, newPassword);
    }

    @Transactional
    public void updatePassword(Long userId, String phone, String newPassword, String username) {
        UserAccount user = userId != null
                ? userAccountStore.findById(userId).orElse(null)
                : userAccountStore.findByUsername(username).orElse(null);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "user not found");
        }
        updatePasswordForUser(user, userId, phone, newPassword);
    }

    @Transactional(readOnly = true)
    public boolean passwordResetTargetMatches(String username, String phone) {
        return passwordResetTargetMatches(username, phone, null);
    }

    @Transactional(readOnly = true)
    public boolean passwordResetTargetMatches(String username, String phone, String email) {
        UserAccount user = userAccountStore.findByUsername(username).orElse(null);
        if (user == null || user.phone() == null || !user.phone().equals(phone)) {
            return false;
        }
        return !StringUtils.hasText(email)
                || (user.email() != null && user.email().equalsIgnoreCase(email.trim()));
    }

    @Transactional
    public void resetPasswordAfterOtp(String username, String phone, String newPassword) {
        updatePassword(null, phone, newPassword, username);
    }

    @Transactional(readOnly = true)
    public Optional<Long> findUserIdByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return Optional.empty();
        }
        return userAccountStore.findByUsername(username).map(UserAccount::id);
    }

    @Transactional(readOnly = true)
    public boolean verifyCurrentPassword(Long userId, String rawPassword) {
        if (userId == null || !StringUtils.hasText(rawPassword)) {
            return false;
        }
        UserAccount user = userAccountStore.findById(userId).orElse(null);
        if (user == null) {
            return false;
        }
        String currentHash = user.passwordHash();
        if (!StringUtils.hasText(currentHash)) {
            return false;
        }
        return passwordHasher.matches(rawPassword, currentHash);
    }

    @Transactional(readOnly = true)
    public boolean verifyAdminTotp(Long userId, String totpCode) {
        UserAccount user = userAccountStore.findById(userId).orElse(null);
        return user != null
                && UserRoles.ADMIN.equals(normalizeRole(user.role()))
                && user.mfaEnabled()
                && totpService.verifyCode(user.totpSecret(), totpCode);
    }

    private void updatePasswordForUser(UserAccount user, Long requestedUserId, String phone, String newPassword) {
        if (user.phone() == null || !user.phone().equals(phone)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "phone verification failed");
        }
        passwordPolicy.validateOrThrow(newPassword);
        Long effectiveUserId = user.id() != null ? user.id() : requestedUserId;
        if (passwordMatchesRecentHistory(effectiveUserId, user.passwordHash(), newPassword)) {
            throw new BusinessException(ErrorCode.CONFLICT, "password was used recently");
        }
        LocalDateTime now = LocalDateTime.now();
        String encodedPassword = passwordHasher.hash(newPassword);
        userAccountStore.save(user.withPassword(encodedPassword, now));
        recordPasswordHistory(effectiveUserId, encodedPassword, now);
    }

    private boolean passwordMatchesRecentHistory(Long userId, String currentPasswordHash, String rawPassword) {
        if (currentPasswordHash != null && passwordHasher.matches(rawPassword, currentPasswordHash)) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        List<String> recentPasswordHashes = userAccountStore.findRecentPasswordHashes(userId);
        if (recentPasswordHashes == null) {
            return false;
        }
        return recentPasswordHashes.stream()
                .anyMatch(previousHash -> passwordHasher.matches(rawPassword, previousHash));
    }

    private static String normalizeRole(String role) {
        return UserRoles.ADMIN.equals(role) ? UserRoles.ADMIN : UserRoles.USER;
    }

    private void recordPasswordHistory(Long userId, String encodedPassword, LocalDateTime changedAt) {
        if (userId == null) {
            return;
        }
        userAccountStore.recordPasswordHistory(userId, encodedPassword, changedAt);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AuthPrincipal principalFor(UserAccount user) {
        return new AuthPrincipal(
                user.id(),
                normalizeRole(user.role()),
                user.authorityNames(),
                passwordChangeRequired(user),
                user.tenantId());
    }

    private static boolean passwordChangeRequired(UserAccount user) {
        return user != null && (user.passwordChangeRequired() || passwordExpired(user.passwordLastChangedAt()));
    }

    private static boolean passwordExpired(LocalDateTime passwordLastChangedAt) {
        return passwordLastChangedAt != null
                && passwordLastChangedAt.isBefore(LocalDateTime.now().minusDays(PASSWORD_EXPIRATION_DAYS));
    }
}

package com.example.monkey.user.infrastructure;

import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.domain.UserRoles;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class JpaUserAccountStore implements UserAccountStore {

    private final UserRepository userRepository;
    private final PasswordHistoryRepository passwordHistoryRepository;
    private final RoleRepository roleRepository;

    public JpaUserAccountStore(
            UserRepository userRepository,
            PasswordHistoryRepository passwordHistoryRepository,
            RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.passwordHistoryRepository = passwordHistoryRepository;
        this.roleRepository = roleRepository;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return Optional.ofNullable(userRepository.findByUsername(username)).map(JpaUserAccountStore::toRecord);
    }

    @Override
    public Optional<UserAccount> findById(Long id) {
        return userRepository.findById(id).map(JpaUserAccountStore::toRecord);
    }

    @Override
    public List<UserAccount> findByRole(String role) {
        return userRepository.findByRole(normalizeRole(role)).stream()
                .map(JpaUserAccountStore::toRecord)
                .toList();
    }

    @Override
    public UserAccount save(UserAccount account) {
        User savedUser = userRepository.save(toEntity(account));
        return toRecord(savedUser != null ? savedUser : toEntity(account));
    }

    @Override
    public List<String> findRecentPasswordHashes(Long userId) {
        return passwordHistoryRepository.findTop5ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(PasswordHistory::getPasswordHash)
                .toList();
    }

    @Override
    public void recordPasswordHistory(Long userId, String passwordHash, LocalDateTime changedAt) {
        PasswordHistory history = new PasswordHistory();
        history.setUserId(userId);
        history.setPasswordHash(passwordHash);
        history.setCreatedAt(changedAt);
        passwordHistoryRepository.save(history);
    }

    private User toEntity(UserAccount account) {
        User user = new User();
        user.setId(account.id());
        user.setUsername(account.username());
        user.setPassword(account.passwordHash());
        user.setPhone(account.phone());
        user.setEmail(account.email());
        user.setAvatar(account.avatar());
        user.setRole(normalizeRole(account.role()));
        user.setNickname(account.nickname());
        user.setPasswordLastChangedAt(account.passwordLastChangedAt());
        user.setPasswordChangeRequired(account.passwordChangeRequired());
        user.setTotpSecret(account.totpSecret());
        user.setMfaEnabled(account.mfaEnabled());
        user.setRoles(new LinkedHashSet<>(List.of(resolveRole(user.getRole()))));
        return user;
    }

    private Role resolveRole(String roleName) {
        Role existing = roleRepository.findByName(roleName);
        if (existing != null) {
            return existing;
        }
        Role role = roleSnapshot(roleName);
        Role savedRole = roleRepository.save(role);
        return savedRole != null ? savedRole : role;
    }

    private static Role roleSnapshot(String roleName) {
        Role role = new Role();
        role.setName(normalizeRole(roleName));
        role.setDescription(role.getName() + " role");
        return role;
    }

    private static UserAccount toRecord(User user) {
        return new UserAccount(
                user.getId(),
                user.getUsername(),
                user.getPassword(),
                user.getPhone(),
                user.getEmail(),
                user.getAvatar(),
                user.primaryRole(),
                user.getNickname(),
                user.getPasswordLastChangedAt(),
                user.isPasswordChangeRequired(),
                user.getTotpSecret(),
                user.isMfaEnabled(),
                authorityNames(user));
    }

    private static List<String> authorityNames(User user) {
        return user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private static String normalizeRole(String roleName) {
        return UserRoles.ADMIN.equals(roleName) ? UserRoles.ADMIN : UserRoles.USER;
    }
}

package com.example.monkey.infrastructure.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.user.UserAccountStore.UserAccount;
import com.example.monkey.domain.user.UserRoles;
import com.example.monkey.entity.PasswordHistory;
import com.example.monkey.entity.Permission;
import com.example.monkey.entity.Role;
import com.example.monkey.entity.User;
import com.example.monkey.repository.PasswordHistoryRepository;
import com.example.monkey.repository.RoleRepository;
import com.example.monkey.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaUserAccountStoreTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHistoryRepository passwordHistoryRepository;

    @Mock
    private RoleRepository roleRepository;

    private JpaUserAccountStore store;

    @BeforeEach
    void setUp() {
        store = new JpaUserAccountStore(userRepository, passwordHistoryRepository, roleRepository);
    }

    @Test
    void findByUsernameMapsEntityToAccountRecordWithAuthorities() {
        when(userRepository.findByUsername("alice")).thenReturn(user());

        Optional<UserAccount> result = store.findByUsername("alice");

        assertThat(result).contains(record());
        assertThat(result.get().authorityNames()).contains("ROLE_USER", "ORDER_READ_OWN");
    }

    @Test
    void findByIdMapsRepositoryOptional() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user()));

        Optional<UserAccount> result = store.findById(7L);

        assertThat(result).contains(record());
    }

    @Test
    void findByRoleMapsUsersToAccountRecords() {
        when(userRepository.findByRole(UserRoles.ADMIN)).thenReturn(List.of(adminUser()));

        List<UserAccount> result = store.findByRole(UserRoles.ADMIN);

        assertThat(result).containsExactly(adminRecord());
    }

    @Test
    void saveMapsRecordThroughRepositoryEntityAndResolvesExistingRole() {
        Role role = roleWithPermission(UserRoles.USER, "ORDER_READ_OWN");
        when(roleRepository.findByName(UserRoles.USER)).thenReturn(role);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserAccount result = store.save(record());

        assertThat(result).isEqualTo(record());
        User savedUser = captureSavedUser();
        assertThat(savedUser.getId()).isEqualTo(7L);
        assertThat(savedUser.getUsername()).isEqualTo("alice");
        assertThat(savedUser.getPassword()).isEqualTo("encoded-password");
        assertThat(savedUser.getPhone()).isEqualTo("18888888888");
        assertThat(savedUser.getRole()).isEqualTo(UserRoles.USER);
        assertThat(savedUser.getRoles()).extracting(Role::getName).containsExactly(UserRoles.USER);
    }

    @Test
    void saveCreatesMissingRoleSnapshotInAdapter() {
        when(roleRepository.findByName(UserRoles.ADMIN)).thenReturn(null);
        when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        store.save(adminRecord());

        Role role = captureSavedRole();
        assertThat(role.getName()).isEqualTo(UserRoles.ADMIN);
        assertThat(role.getDescription()).isEqualTo("ADMIN role");
    }

    @Test
    void findRecentPasswordHashesMapsHistoryEntities() {
        PasswordHistory first = history("first-hash");
        PasswordHistory second = history("second-hash");
        when(passwordHistoryRepository.findTop5ByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(first, second));

        List<String> result = store.findRecentPasswordHashes(7L);

        assertThat(result).containsExactly("first-hash", "second-hash");
    }

    @Test
    void recordPasswordHistoryCreatesHistoryEntity() {
        LocalDateTime changedAt = LocalDateTime.parse("2026-06-29T12:00:00");

        store.recordPasswordHistory(7L, "encoded-password", changedAt);

        PasswordHistory history = captureSavedHistory();
        assertThat(history.getUserId()).isEqualTo(7L);
        assertThat(history.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(history.getCreatedAt()).isEqualTo(changedAt);
    }

    private User captureSavedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }

    private Role captureSavedRole() {
        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository).save(captor.capture());
        return captor.getValue();
    }

    private PasswordHistory captureSavedHistory() {
        ArgumentCaptor<PasswordHistory> captor = ArgumentCaptor.forClass(PasswordHistory.class);
        verify(passwordHistoryRepository).save(captor.capture());
        return captor.getValue();
    }

    private static User user() {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setPassword("encoded-password");
        user.setPhone("18888888888");
        user.setEmail("alice@example.com");
        user.setAvatar("/avatar.png");
        user.setPasswordLastChangedAt(LocalDateTime.parse("2026-06-29T12:00:00"));
        Role role = role(UserRoles.USER);
        Permission permission = new Permission();
        permission.setName("ORDER_READ_OWN");
        role.setPermissions(new LinkedHashSet<>(List.of(permission)));
        user.setRoles(new LinkedHashSet<>(List.of(role)));
        user.setRole(UserRoles.USER);
        return user;
    }

    private static User adminUser() {
        User user = user();
        Role role = role(UserRoles.ADMIN);
        user.setRoles(new LinkedHashSet<>(List.of(role)));
        user.setRole(UserRoles.ADMIN);
        return user;
    }

    private static Role role(String name) {
        Role role = new Role();
        role.setName(name);
        return role;
    }

    private static Role roleWithPermission(String name, String permissionName) {
        Role role = role(name);
        Permission permission = new Permission();
        permission.setName(permissionName);
        role.setPermissions(new LinkedHashSet<>(List.of(permission)));
        return role;
    }

    private static PasswordHistory history(String passwordHash) {
        PasswordHistory history = new PasswordHistory();
        history.setUserId(7L);
        history.setPasswordHash(passwordHash);
        history.setCreatedAt(LocalDateTime.parse("2026-06-29T12:00:00"));
        return history;
    }

    private static UserAccount record() {
        return new UserAccount(
                7L,
                "alice",
                "encoded-password",
                "18888888888",
                "alice@example.com",
                "/avatar.png",
                UserRoles.USER,
                null,
                LocalDateTime.parse("2026-06-29T12:00:00"),
                false,
                null,
                false,
                List.of("ROLE_USER", "ORDER_READ_OWN"));
    }

    private static UserAccount adminRecord() {
        UserAccount record = record();
        return new UserAccount(
                record.id(),
                record.username(),
                record.passwordHash(),
                record.phone(),
                record.email(),
                record.avatar(),
                UserRoles.ADMIN,
                record.nickname(),
                record.passwordLastChangedAt(),
                record.passwordChangeRequired(),
                record.totpSecret(),
                record.mfaEnabled(),
                List.of("ROLE_ADMIN"));
    }
}

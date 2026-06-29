package com.example.monkey.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.domain.user.UserRoles;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class UserSecurityDetailsTest {

    @Test
    void grantsUserRoleByDefault() {
        User user = new User();

        assertThat(user.getAuthorities()).extracting("authority").containsExactly("ROLE_" + UserRoles.USER);
    }

    @Test
    void grantsAdminRoleWhenUserIsAdmin() {
        User user = new User();
        user.setRole(UserRoles.ADMIN);

        assertThat(user.getAuthorities()).extracting("authority").containsExactly("ROLE_" + UserRoles.ADMIN);
    }

    @Test
    void grantsRoleAndPermissionAuthoritiesFromAssignedRoles() {
        Permission permission = new Permission();
        permission.setName("ORDER_CREATE");
        Role role = new Role();
        role.setName(UserRoles.USER);
        role.setPermissions(new java.util.LinkedHashSet<>(java.util.List.of(permission)));

        User user = new User();
        user.addRole(role);

        assertThat(user.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_" + UserRoles.USER, "ORDER_CREATE");
    }

    @Test
    void credentialsExpireAfterNinetyDays() {
        User user = new User();
        user.setPasswordLastChangedAt(LocalDateTime.now().minusDays(91));

        assertThat(user.isCredentialsNonExpired()).isFalse();
    }

    @Test
    void recentlyChangedCredentialsAreNotExpired() {
        User user = new User();
        user.setPasswordLastChangedAt(LocalDateTime.now().minusDays(10));

        assertThat(user.isCredentialsNonExpired()).isTrue();
    }
}

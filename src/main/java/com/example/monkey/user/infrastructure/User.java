package com.example.monkey.user.infrastructure;

import com.example.monkey.shared.domain.privacy.PhoneBlindIndexTarget;
import com.example.monkey.shared.infrastructure.privacy.EncryptedStringAttributeConverter;
import com.example.monkey.shared.infrastructure.privacy.PiiBlindIndexEntityListener;
import com.example.monkey.shared.infrastructure.tenant.TenantScopedJpaEntity;
import com.example.monkey.user.domain.UserRoles;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Entity
@Table(name = "user")
@EntityListeners(PiiBlindIndexEntityListener.class)
public class User extends TenantScopedJpaEntity implements UserDetails, PhoneBlindIndexTarget {
    private static final int PASSWORD_EXPIRATION_DAYS = 90;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;
    private String password;

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 1024)
    private String phone;

    @Column(name = "phone_hmac", columnDefinition = "CHAR(64)")
    private String phoneHmac;

    @Convert(converter = EncryptedStringAttributeConverter.class)
    @Column(length = 1024)
    private String email;

    private String avatar;
    private String role;
    private String nickname;

    @Column(name = "password_last_changed_at")
    private LocalDateTime passwordLastChangedAt;

    @Column(name = "password_change_required")
    private boolean passwordChangeRequired;

    @JsonIgnore
    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "mfa_enabled")
    private boolean mfaEnabled;

    @Column(name = "create_time", insertable = false, updatable = false)
    private LocalDateTime createTime;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @Override
    @JsonIgnore
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    @JsonIgnore
    public String getPhoneHmac() {
        return phoneHmac;
    }

    public void setPhoneHmac(String phoneHmac) {
        this.phoneHmac = phoneHmac;
    }

    @Override
    @Transient
    @JsonIgnore
    public String phoneValueForBlindIndex() {
        return phone;
    }

    @Override
    public void setPhoneBlindIndex(String blindIndex) {
        this.phoneHmac = blindIndex;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public LocalDateTime getPasswordLastChangedAt() {
        return passwordLastChangedAt;
    }

    public void setPasswordLastChangedAt(LocalDateTime passwordLastChangedAt) {
        this.passwordLastChangedAt = passwordLastChangedAt;
    }

    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    public void setPasswordChangeRequired(boolean passwordChangeRequired) {
        this.passwordChangeRequired = passwordChangeRequired;
    }

    @JsonIgnore
    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public void setMfaEnabled(boolean mfaEnabled) {
        this.mfaEnabled = mfaEnabled;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    @JsonIgnore
    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public void addRole(Role role) {
        if (role != null) {
            if (roles == null) {
                roles = new LinkedHashSet<>();
            }
            roles.add(role);
        }
    }

    @Override
    @Transient
    @JsonIgnore
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<String> authorityNames = new LinkedHashSet<>();
        for (Role assignedRole : effectiveRoles()) {
            String roleName = normalizedRole(assignedRole == null ? null : assignedRole.getName());
            authorityNames.add("ROLE_" + roleName);
            if (assignedRole != null && assignedRole.getPermissions() != null) {
                assignedRole.getPermissions().stream()
                        .map(Permission::getName)
                        .filter(name -> name != null && !name.isBlank())
                        .forEach(authorityNames::add);
            }
        }
        if (authorityNames.isEmpty()) {
            authorityNames.add("ROLE_" + primaryRole());
        }
        return authorityNames.stream().map(SimpleGrantedAuthority::new).toList();
    }

    @Override
    @Transient
    @JsonIgnore
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    @Transient
    @JsonIgnore
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    @Transient
    @JsonIgnore
    public boolean isCredentialsNonExpired() {
        return passwordLastChangedAt == null
                || !passwordLastChangedAt.isBefore(LocalDateTime.now().minusDays(PASSWORD_EXPIRATION_DAYS));
    }

    @Override
    @Transient
    @JsonIgnore
    public boolean isEnabled() {
        return true;
    }

    @Transient
    @JsonIgnore
    public String primaryRole() {
        if (UserRoles.ADMIN.equals(role)) {
            return UserRoles.ADMIN;
        }
        if (roles != null
                && roles.stream()
                        .anyMatch(assignedRole -> assignedRole != null
                                && UserRoles.ADMIN.equals(normalizedRole(assignedRole.getName())))) {
            return UserRoles.ADMIN;
        }
        return UserRoles.USER;
    }

    private List<Role> effectiveRoles() {
        if (roles != null && !roles.isEmpty()) {
            return roles.stream().toList();
        }
        Role fallbackRole = new Role();
        fallbackRole.setName(primaryRole());
        return List.of(fallbackRole);
    }

    private static String normalizedRole(String roleName) {
        return UserRoles.ADMIN.equals(roleName) ? UserRoles.ADMIN : UserRoles.USER;
    }
}

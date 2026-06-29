package com.example.monkey.domain.user;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

public record AuthPrincipal(Long userId, String role, List<String> authorities, boolean passwordChangeRequired) {

    public AuthPrincipal(Long userId, String role) {
        this(userId, role, List.of(), false);
    }

    public AuthPrincipal(Long userId, String role, List<String> authorities) {
        this(userId, role, authorities, false);
    }

    public AuthPrincipal {
        authorities = normalizeAuthorities(role, authorities);
    }

    private static List<String> normalizeAuthorities(String role, Collection<String> authorityNames) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        String normalizedRole = UserRoles.ADMIN.equals(role) ? UserRoles.ADMIN : UserRoles.USER;
        normalized.add("ROLE_" + normalizedRole);
        if (authorityNames != null) {
            authorityNames.stream()
                    .filter(AuthPrincipal::hasText)
                    .map(String::trim)
                    .forEach(normalized::add);
        }
        return List.copyOf(normalized);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

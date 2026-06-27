package com.example.monkey.security;

import jakarta.servlet.http.HttpSession;
import java.util.Optional;

public final class SessionIdentity {

    public static final String USER_ID_ATTRIBUTE = "USER_ID";
    public static final String IDENTITY_ATTRIBUTE = "IDENTITY";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    private SessionIdentity() {
    }

    public static Optional<SessionUser> fromSession(HttpSession session) {
        if (session == null) {
            return Optional.empty();
        }
        Object userId = session.getAttribute(USER_ID_ATTRIBUTE);
        Object identity = session.getAttribute(IDENTITY_ATTRIBUTE);
        if (!(userId instanceof Long id) || !(identity instanceof String role)) {
            return Optional.empty();
        }
        if (!ROLE_ADMIN.equals(role) && !ROLE_USER.equals(role)) {
            return Optional.empty();
        }
        return Optional.of(new SessionUser(id, role));
    }
}

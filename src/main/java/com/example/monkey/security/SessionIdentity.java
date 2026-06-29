package com.example.monkey.security;

import com.example.monkey.domain.user.UserRoles;

public final class SessionIdentity {

    public static final String ROLE_ADMIN = UserRoles.ADMIN;
    public static final String ROLE_USER = UserRoles.USER;

    private SessionIdentity() {}
}

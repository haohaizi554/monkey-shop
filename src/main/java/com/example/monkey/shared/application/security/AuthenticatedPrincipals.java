package com.example.monkey.shared.application.security;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;

public final class AuthenticatedPrincipals {

    private AuthenticatedPrincipals() {}

    public static Long requireUserId(SessionUser currentUser) {
        if (currentUser == null || currentUser.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return currentUser.id();
    }
}

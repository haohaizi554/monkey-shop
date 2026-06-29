package com.example.monkey.domain.user;

import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;

public final class AuthenticatedPrincipals {

    private AuthenticatedPrincipals() {}

    public static Long requireUserId(SessionUser currentUser) {
        if (currentUser == null || currentUser.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return currentUser.id();
    }
}

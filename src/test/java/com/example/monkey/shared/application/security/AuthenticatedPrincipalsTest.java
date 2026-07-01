package com.example.monkey.shared.application.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class AuthenticatedPrincipalsTest {

    @Test
    void requireUserIdReturnsPrincipalId() {
        assertThat(AuthenticatedPrincipals.requireUserId(new SessionUser(7L, "USER")))
                .isEqualTo(7L);
    }

    @Test
    void requireUserIdRejectsMissingPrincipal() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> AuthenticatedPrincipals.requireUserId(null))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void requireUserIdRejectsPrincipalWithoutId() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> AuthenticatedPrincipals.requireUserId(new SessionUser(null, "USER")))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }
}

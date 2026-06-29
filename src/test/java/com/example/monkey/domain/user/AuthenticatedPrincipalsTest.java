package com.example.monkey.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

class AuthenticatedPrincipalsTest {

    @Test
    void requireUserIdReturnsPrincipalId() {
        assertThat(AuthenticatedPrincipals.requireUserId(new SessionUser(7L, UserRoles.USER)))
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
                .isThrownBy(() -> AuthenticatedPrincipals.requireUserId(new SessionUser(null, UserRoles.USER)))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
    }
}

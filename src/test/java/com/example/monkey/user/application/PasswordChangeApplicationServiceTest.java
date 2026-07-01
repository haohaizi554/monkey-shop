package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PasswordChangeApplicationServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private AuditService auditService;

    private PasswordChangeApplicationService passwordChangeService;

    @BeforeEach
    void setUp() {
        passwordChangeService = new PasswordChangeApplicationService(userService, captchaService, auditService);
    }

    @Test
    void changePasswordAuditsCaptchaFailureBeforeUpdatingPassword() {
        when(captchaService.validate("challenge-id", "bad", "change-password", "127.0.0.1"))
                .thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> passwordChangeService.changePassword(
                        user(), "challenge-id", "bad", "18888888888", "StrongPass1!", "127.0.0.1"))
                .withMessage("captcha incorrect")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(auditService)
                .record(
                        AuditService.PASSWORD_CHANGE_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        7L,
                        "USER",
                        null,
                        "127.0.0.1",
                        "captcha_invalid");
        verifyNoInteractions(userService);
    }

    @Test
    void changePasswordAuditsSuccessAfterPasswordUpdate() {
        when(captchaService.validate("challenge-id", "1234", "change-password", "203.0.113.10"))
                .thenReturn(true);

        PasswordChangeApplicationService.PasswordChangeResult result = passwordChangeService.changePassword(
                user(), "challenge-id", "1234", "18888888888", "StrongPass1!", "203.0.113.10");

        assertThat(result.userIdToRevoke()).isEqualTo(7L);
        verify(userService).updatePassword(7L, "18888888888", "StrongPass1!", null);
        verify(auditService)
                .record(
                        AuditService.PASSWORD_CHANGE_SUCCESS,
                        AuditService.OUTCOME_SUCCESS,
                        7L,
                        "USER",
                        null,
                        "203.0.113.10",
                        null);
    }

    @Test
    void changePasswordAuditsApplicationFailureAndRethrows() {
        when(captchaService.validate("challenge-id", "1234", "change-password", "203.0.113.10"))
                .thenReturn(true);
        BusinessException failure = new BusinessException(ErrorCode.CONFLICT, "password was used recently");
        doThrow(failure).when(userService).updatePassword(7L, "18888888888", "StrongPass1!", null);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> passwordChangeService.changePassword(
                        user(), "challenge-id", "1234", "18888888888", "StrongPass1!", "203.0.113.10"))
                .isSameAs(failure);

        verify(auditService)
                .record(
                        AuditService.PASSWORD_CHANGE_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        7L,
                        "USER",
                        null,
                        "203.0.113.10",
                        "password was used recently");
    }

    private static SessionUser user() {
        return new SessionUser(7L, "USER");
    }
}

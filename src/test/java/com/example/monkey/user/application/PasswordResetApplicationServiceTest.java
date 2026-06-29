package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PasswordResetApplicationServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private PasswordResetChallengeApplicationService passwordResetChallengeService;

    @Mock
    private AuditService auditService;

    private PasswordResetApplicationService passwordResetService;

    @BeforeEach
    void setUp() {
        passwordResetService = new PasswordResetApplicationService(
                userService, captchaService, passwordResetChallengeService, auditService);
    }

    @Test
    void requestPasswordResetIssuesChallengeWhenTargetMatchesAndAuditsAccepted() {
        when(userService.passwordResetTargetMatches("alice", "18888888888", null))
                .thenReturn(true);

        passwordResetService.requestPasswordReset("alice", "18888888888", null, null, null, "127.0.0.1");

        verify(passwordResetChallengeService).issueResetChallenge("alice", "18888888888", null, true);
        verify(auditService)
                .record(
                        AuditService.PASSWORD_RESET_REQUEST,
                        AuditService.OUTCOME_ACCEPTED,
                        null,
                        null,
                        "alice",
                        "127.0.0.1",
                        "accepted");
    }

    @Test
    void requestPasswordResetIssuesDualChannelChallengeWhenEmailMatches() {
        when(userService.passwordResetTargetMatches("alice", "18888888888", "alice@example.com"))
                .thenReturn(true);

        passwordResetService.requestPasswordReset("alice", "18888888888", "alice@example.com", null, null, "127.0.0.1");

        verify(passwordResetChallengeService).issueResetChallenge("alice", "18888888888", "alice@example.com", true);
    }

    @Test
    void requestPasswordResetAuditsExternalCaptchaFailureBeforeIssuingChallenge() {
        when(captchaService.externalProviderEnabled()).thenReturn(true);
        when(captchaService.validate("challenge-id", "bad", "password-reset-request", "203.0.113.10"))
                .thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> passwordResetService.requestPasswordReset(
                        "alice", "18888888888", null, "bad", "challenge-id", "203.0.113.10"))
                .withMessage(PasswordResetApplicationService.PASSWORD_RESET_CAPTCHA_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(auditService)
                .record(
                        AuditService.PASSWORD_RESET_REQUEST,
                        AuditService.OUTCOME_DENIED,
                        null,
                        null,
                        "alice",
                        "203.0.113.10",
                        "captcha_invalid");
        verifyNoInteractions(userService, passwordResetChallengeService);
    }

    @Test
    void requestPasswordResetAuditsRateLimitExceptionFromChallengeIssuance() {
        when(userService.passwordResetTargetMatches("alice", "18888888888", null))
                .thenReturn(true);
        doThrow(new BusinessException(ErrorCode.RATE_LIMIT, "too many reset requests"))
                .when(passwordResetChallengeService)
                .issueResetChallenge("alice", "18888888888", null, true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> passwordResetService.requestPasswordReset(
                        "alice", "18888888888", null, null, null, "127.0.0.1"))
                .withMessage("too many reset requests")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT));

        verify(auditService)
                .record(
                        AuditService.PASSWORD_RESET_REQUEST,
                        AuditService.OUTCOME_DENIED,
                        null,
                        null,
                        "alice",
                        "127.0.0.1",
                        "rate_limit");
    }

    @Test
    void resetPasswordRequiresOtpBeforeChangingPassword() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> passwordResetService.resetPassword(
                        "alice", "18888888888", null, " ", null, "StrongPass1!", null, null, "127.0.0.1"))
                .withMessage(PasswordResetApplicationService.PASSWORD_RESET_OTP_REQUIRED)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(auditService)
                .record(
                        AuditService.PASSWORD_RESET_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        null,
                        null,
                        "alice",
                        "127.0.0.1",
                        "missing_otp");
        verifyNoInteractions(passwordResetChallengeService, userService);
    }

    @Test
    void resetPasswordRequiresValidOtpBeforeChangingPassword() {
        when(passwordResetChallengeService.consumeResetOtp("alice", "18888888888", "654321"))
                .thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> passwordResetService.resetPassword(
                        "alice", "18888888888", null, "654321", null, "StrongPass1!", null, null, "127.0.0.1"))
                .withMessage(PasswordResetApplicationService.PASSWORD_RESET_OTP_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(userService, never()).resetPasswordAfterOtp("alice", "18888888888", "StrongPass1!");
        verify(auditService)
                .record(
                        AuditService.PASSWORD_RESET_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        null,
                        null,
                        "alice",
                        "127.0.0.1",
                        "invalid_otp");
    }

    @Test
    void resetPasswordAuditsExternalCaptchaFailureBeforeConsumingOtp() {
        when(captchaService.externalProviderEnabled()).thenReturn(true);
        when(captchaService.validate("challenge-id", "bad", "password-reset", "203.0.113.11"))
                .thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> passwordResetService.resetPassword(
                        "alice",
                        "18888888888",
                        null,
                        "654321",
                        null,
                        "StrongPass1!",
                        "bad",
                        "challenge-id",
                        "203.0.113.11"))
                .withMessage(PasswordResetApplicationService.PASSWORD_RESET_CAPTCHA_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(auditService)
                .record(
                        AuditService.PASSWORD_RESET_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        null,
                        null,
                        "alice",
                        "203.0.113.11",
                        "captcha_invalid");
        verifyNoInteractions(passwordResetChallengeService, userService);
    }

    @Test
    void resetPasswordUpdatesPasswordAfterOtpIsConsumedAndReturnsUserIdForTokenRevocation() {
        when(passwordResetChallengeService.consumeResetOtp("alice", "18888888888", "654321"))
                .thenReturn(true);
        when(userService.findUserIdByUsername("alice")).thenReturn(Optional.of(7L));

        var result = passwordResetService.resetPassword(
                "alice", "18888888888", null, "654321", null, "StrongPass1!", null, null, "127.0.0.1");

        assertThat(result.userIdToRevoke()).isEqualTo(7L);
        verify(userService).resetPasswordAfterOtp("alice", "18888888888", "StrongPass1!");
        verify(auditService)
                .record(
                        AuditService.PASSWORD_RESET_SUCCESS,
                        AuditService.OUTCOME_SUCCESS,
                        null,
                        null,
                        "alice",
                        "127.0.0.1",
                        null);
    }

    @Test
    void resetPasswordDoesNotExposeMissingUserAfterOtpIsConsumed() {
        when(passwordResetChallengeService.consumeResetOtp("alice", "18888888888", "654321"))
                .thenReturn(true);
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "user not found"))
                .when(userService)
                .resetPasswordAfterOtp("alice", "18888888888", "StrongPass1!");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> passwordResetService.resetPassword(
                        "alice", "18888888888", null, "654321", null, "StrongPass1!", null, null, "127.0.0.1"))
                .withMessage(PasswordResetApplicationService.PASSWORD_RESET_FAILED)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(auditService)
                .record(
                        AuditService.PASSWORD_RESET_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        null,
                        null,
                        "alice",
                        "127.0.0.1",
                        PasswordResetApplicationService.PASSWORD_RESET_FAILED);
    }

    @Test
    void resetPasswordReturnsPasswordPolicyErrorsAfterOtpIsConsumed() {
        when(passwordResetChallengeService.consumeResetOtp("alice", "18888888888", "654321"))
                .thenReturn(true);
        doThrow(new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "password policy violation: password must contain a special character"))
                .when(userService)
                .resetPasswordAfterOtp("alice", "18888888888", "Password1");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> passwordResetService.resetPassword(
                        "alice", "18888888888", null, "654321", null, "Password1", null, null, "127.0.0.1"))
                .withMessage("password policy violation: password must contain a special character")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(auditService)
                .record(
                        AuditService.PASSWORD_RESET_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        null,
                        null,
                        "alice",
                        "127.0.0.1",
                        "password policy violation_ password must contain a special character");
    }

    @Test
    void resetPasswordAcceptsDualChannelEmailTokenChallenge() {
        when(passwordResetChallengeService.consumeResetChallenge(
                        "alice", "18888888888", "alice@example.com", "654321", "email-token"))
                .thenReturn(true);
        when(userService.findUserIdByUsername("alice")).thenReturn(Optional.of(7L));

        var result = passwordResetService.resetPassword(
                "alice",
                "18888888888",
                "alice@example.com",
                "654321",
                "email-token",
                "StrongPass1!",
                null,
                null,
                "127.0.0.1");

        assertThat(result.userIdToRevoke()).isEqualTo(7L);
        verify(userService).resetPasswordAfterOtp("alice", "18888888888", "StrongPass1!");
    }
}

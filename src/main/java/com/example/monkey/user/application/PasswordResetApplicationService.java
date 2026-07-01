package com.example.monkey.user.application;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PasswordResetApplicationService {

    static final String PASSWORD_RESET_OTP_REQUIRED = "reset otp required";
    static final String PASSWORD_RESET_OTP_INVALID = "invalid or expired reset otp";
    static final String PASSWORD_RESET_FAILED = "password reset failed";
    static final String PASSWORD_RESET_CAPTCHA_INVALID = "captcha incorrect";
    private static final String ACTION_PASSWORD_RESET_REQUEST = "password-reset-request";
    private static final String ACTION_PASSWORD_RESET = "password-reset";

    private final UserService userService;
    private final CaptchaService captchaService;
    private final PasswordResetChallengeApplicationService passwordResetChallengeService;
    private final AuditService auditService;

    public PasswordResetApplicationService(
            UserService userService,
            CaptchaService captchaService,
            PasswordResetChallengeApplicationService passwordResetChallengeService,
            AuditService auditService) {
        this.userService = userService;
        this.captchaService = captchaService;
        this.passwordResetChallengeService = passwordResetChallengeService;
        this.auditService = auditService;
    }

    public void requestPasswordReset(
            String username, String phone, String email, String captcha, String captchaChallengeId, String clientIp) {
        if (captchaService.externalProviderEnabled()
                && !validateCaptcha(captchaChallengeId, captcha, ACTION_PASSWORD_RESET_REQUEST, clientIp)) {
            auditService.record(
                    AuditService.PASSWORD_RESET_REQUEST,
                    AuditService.OUTCOME_DENIED,
                    null,
                    null,
                    username,
                    clientIp,
                    "captcha_invalid");
            throw authFailure(ErrorCode.VALIDATION_ERROR, PASSWORD_RESET_CAPTCHA_INVALID);
        }
        boolean targetMatches = userService.passwordResetTargetMatches(username, phone, email);
        try {
            passwordResetChallengeService.issueResetChallenge(username, phone, email, targetMatches);
            auditService.record(
                    AuditService.PASSWORD_RESET_REQUEST,
                    AuditService.OUTCOME_ACCEPTED,
                    null,
                    null,
                    username,
                    clientIp,
                    "accepted");
        } catch (BusinessException exception) {
            auditService.record(
                    AuditService.PASSWORD_RESET_REQUEST,
                    AuditService.OUTCOME_DENIED,
                    null,
                    null,
                    username,
                    clientIp,
                    auditDetailFor(exception));
            throw exception;
        }
    }

    public PasswordResetResult resetPassword(
            String username,
            String phone,
            String email,
            String otp,
            String emailToken,
            String newPassword,
            String captcha,
            String captchaChallengeId,
            String clientIp) {
        if (captchaService.externalProviderEnabled()
                && !validateCaptcha(captchaChallengeId, captcha, ACTION_PASSWORD_RESET, clientIp)) {
            auditService.record(
                    AuditService.PASSWORD_RESET_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    null,
                    null,
                    username,
                    clientIp,
                    "captcha_invalid");
            throw authFailure(ErrorCode.VALIDATION_ERROR, PASSWORD_RESET_CAPTCHA_INVALID);
        }
        if (!StringUtils.hasText(otp)) {
            auditService.record(
                    AuditService.PASSWORD_RESET_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    null,
                    null,
                    username,
                    clientIp,
                    "missing_otp");
            throw authFailure(ErrorCode.VALIDATION_ERROR, PASSWORD_RESET_OTP_REQUIRED);
        }
        if (!consumeResetChallenge(username, phone, email, otp, emailToken)) {
            auditService.record(
                    AuditService.PASSWORD_RESET_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    null,
                    null,
                    username,
                    clientIp,
                    "invalid_otp");
            throw authFailure(ErrorCode.VALIDATION_ERROR, PASSWORD_RESET_OTP_INVALID);
        }
        try {
            userService.resetPasswordAfterOtp(username, phone, newPassword);
            Long userIdToRevoke = userService.findUserIdByUsername(username).orElse(null);
            auditService.record(
                    AuditService.PASSWORD_RESET_SUCCESS,
                    AuditService.OUTCOME_SUCCESS,
                    null,
                    null,
                    username,
                    clientIp,
                    null);
            return new PasswordResetResult(userIdToRevoke);
        } catch (BusinessException exception) {
            BusinessException publicException = publicPasswordResetFailure(exception);
            auditService.record(
                    AuditService.PASSWORD_RESET_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    null,
                    null,
                    username,
                    clientIp,
                    auditDetail(publicException.getMessage()));
            throw publicException;
        }
    }

    private boolean validateCaptcha(String challengeId, String captcha, String action, String clientIp) {
        return captchaService.validate(challengeId, captcha, action, clientIp);
    }

    private boolean consumeResetChallenge(String username, String phone, String email, String otp, String emailToken) {
        return StringUtils.hasText(email) || StringUtils.hasText(emailToken)
                ? passwordResetChallengeService.consumeResetChallenge(username, phone, email, otp, emailToken)
                : passwordResetChallengeService.consumeResetOtp(username, phone, otp);
    }

    private static String auditDetail(String result) {
        if (!StringUtils.hasText(result)) {
            return "unknown";
        }
        return result.replace(':', '_');
    }

    private static String auditDetailFor(BusinessException exception) {
        return ErrorCode.RATE_LIMIT.equals(exception.errorCode()) ? "rate_limit" : auditDetail(exception.getMessage());
    }

    private static BusinessException publicPasswordResetFailure(BusinessException exception) {
        if (isUserActionablePasswordResetError(exception.getMessage())) {
            return exception;
        }
        return authFailure(ErrorCode.VALIDATION_ERROR, PASSWORD_RESET_FAILED);
    }

    private static boolean isUserActionablePasswordResetError(String result) {
        if (!StringUtils.hasText(result)) {
            return false;
        }
        return result.startsWith("password policy violation") || "password was used recently".equals(result);
    }

    private static BusinessException authFailure(ErrorCode errorCode, String message) {
        String publicMessage = StringUtils.hasText(message) ? message : errorCode.defaultMessage();
        return new BusinessException(errorCode, publicMessage);
    }

    public record PasswordResetResult(Long userIdToRevoke) {}
}

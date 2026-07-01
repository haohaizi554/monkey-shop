package com.example.monkey.user.application;

import static com.example.monkey.shared.application.security.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PasswordChangeApplicationService {
    static final String ACTION_CHANGE_PASSWORD = "change-password";
    static final String CAPTCHA_INVALID = "captcha incorrect";

    private final UserService userService;
    private final CaptchaService captchaService;
    private final AuditService auditService;

    public PasswordChangeApplicationService(
            UserService userService, CaptchaService captchaService, AuditService auditService) {
        this.userService = userService;
        this.captchaService = captchaService;
        this.auditService = auditService;
    }

    public PasswordChangeResult changePassword(
            SessionUser currentUser,
            String captchaChallengeId,
            String captcha,
            String phone,
            String newPassword,
            String clientIp) {
        Long userId = requireUserId(currentUser);
        if (!captchaService.validate(captchaChallengeId, captcha, ACTION_CHANGE_PASSWORD, clientIp)) {
            auditService.record(
                    AuditService.PASSWORD_CHANGE_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    userId,
                    currentUser.role(),
                    null,
                    clientIp,
                    "captcha_invalid");
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, CAPTCHA_INVALID);
        }

        try {
            userService.updatePassword(userId, phone, newPassword, null);
            auditService.record(
                    AuditService.PASSWORD_CHANGE_SUCCESS,
                    AuditService.OUTCOME_SUCCESS,
                    userId,
                    currentUser.role(),
                    null,
                    clientIp,
                    null);
            return new PasswordChangeResult(userId);
        } catch (BusinessException exception) {
            auditService.record(
                    AuditService.PASSWORD_CHANGE_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    userId,
                    currentUser.role(),
                    null,
                    clientIp,
                    auditDetail(exception.getMessage()));
            throw exception;
        }
    }

    public record PasswordChangeResult(Long userIdToRevoke) {}

    private static String auditDetail(String result) {
        if (!StringUtils.hasText(result)) {
            return "unknown";
        }
        return result.replace(':', '_');
    }
}

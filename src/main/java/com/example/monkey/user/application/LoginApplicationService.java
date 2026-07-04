package com.example.monkey.user.application;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionTokenPair;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.application.dto.AuthLoginResponseDto;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LoginApplicationService {

    static final String ADMIN_MFA_REQUIRED = "admin mfa required";
    static final String ADMIN_MFA_INVALID = "admin mfa invalid";
    static final String LOGIN_CAPTCHA_REQUIRED = "captcha required";
    static final String LOGIN_CAPTCHA_INVALID = "captcha incorrect";
    static final String LOGIN_BAD_CREDENTIALS = "username or password is incorrect";
    private static final String ACTION_LOGIN = "login";

    private final AuthenticationApplicationService authenticationService;
    private final CaptchaService captchaService;
    private final LoginAttemptApplicationService loginAttemptService;
    private final SessionTokenApplicationService tokenService;
    private final AuditService auditService;
    private final AuthResponseService authResponseService;

    public LoginApplicationService(
            AuthenticationApplicationService authenticationService,
            CaptchaService captchaService,
            LoginAttemptApplicationService loginAttemptService,
            SessionTokenApplicationService tokenService,
            AuditService auditService,
            AuthResponseService authResponseService) {
        this.authenticationService = authenticationService;
        this.captchaService = captchaService;
        this.loginAttemptService = loginAttemptService;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.authResponseService = authResponseService;
    }

    public LoginResult login(
            String username, String password, String captcha, String totp, String captchaChallengeId, String clientIp) {
        try {
            loginAttemptService.enforceAllowed(username, clientIp);
        } catch (BusinessException exception) {
            auditService.record(
                    AuditService.LOGIN_RATE_LIMITED,
                    AuditService.OUTCOME_DENIED,
                    null,
                    null,
                    username,
                    clientIp,
                    "rate_limit");
            throw exception;
        }
        if (captchaService.externalProviderEnabled() || loginAttemptService.requiresCaptcha(username, clientIp)) {
            requireValidCaptcha(username, captcha, captchaChallengeId, clientIp);
        }

        AuthenticatedUserPrincipal principal = authenticationService.authenticate(username, password);
        if (principal == null) {
            loginAttemptService.recordFailure(username, clientIp);
            auditService.record(
                    AuditService.LOGIN_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    null,
                    null,
                    username,
                    clientIp,
                    "bad_credentials");
            throw authFailure(ErrorCode.UNAUTHORIZED, LOGIN_BAD_CREDENTIALS);
        }
        verifyAdminMfaIfRequired(username, totp, principal, clientIp);

        loginAttemptService.recordSuccess(username, clientIp);
        auditService.record(
                AuditService.LOGIN_SUCCESS,
                AuditService.OUTCOME_SUCCESS,
                principal.userId(),
                principal.role(),
                username,
                clientIp,
                null);
        SessionTokenPair tokenPair = tokenService.issueTokenPair(
                principal.userId(), principal.role(), principal.authorities(), principal.tenantId());
        return new LoginResult(
                tokenPair, authResponseService.loginResponse(principal.role(), principal.passwordChangeRequired()));
    }

    private void requireValidCaptcha(String username, String captcha, String captchaChallengeId, String clientIp) {
        if (!StringUtils.hasText(captcha)) {
            auditService.record(
                    AuditService.LOGIN_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    null,
                    null,
                    username,
                    clientIp,
                    "captcha_required");
            throw authFailure(ErrorCode.VALIDATION_ERROR, LOGIN_CAPTCHA_REQUIRED);
        }
        if (!captchaService.validate(captchaChallengeId, captcha, ACTION_LOGIN, clientIp)) {
            loginAttemptService.recordFailure(username, clientIp);
            auditService.record(
                    AuditService.LOGIN_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    null,
                    null,
                    username,
                    clientIp,
                    "captcha_invalid");
            throw authFailure(ErrorCode.VALIDATION_ERROR, LOGIN_CAPTCHA_INVALID);
        }
    }

    private void verifyAdminMfaIfRequired(
            String username, String totp, AuthenticatedUserPrincipal principal, String clientIp) {
        if (!UserRoleNames.ADMIN.equals(principal.role())) {
            return;
        }
        if (!StringUtils.hasText(totp)) {
            loginAttemptService.recordFailure(username, clientIp);
            auditService.record(
                    AuditService.ADMIN_MFA_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    principal.userId(),
                    principal.role(),
                    username,
                    clientIp,
                    "missing_totp");
            throw authFailure(ErrorCode.UNAUTHORIZED, ADMIN_MFA_REQUIRED);
        }
        if (!authenticationService.verifyAdminTotp(principal.userId(), totp)) {
            loginAttemptService.recordFailure(username, clientIp);
            auditService.record(
                    AuditService.ADMIN_MFA_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    principal.userId(),
                    principal.role(),
                    username,
                    clientIp,
                    "invalid_totp");
            throw authFailure(ErrorCode.UNAUTHORIZED, ADMIN_MFA_INVALID);
        }
    }

    private static BusinessException authFailure(ErrorCode errorCode, String message) {
        String publicMessage = StringUtils.hasText(message) ? message : errorCode.defaultMessage();
        return new BusinessException(errorCode, publicMessage);
    }

    public record LoginResult(SessionTokenPair tokenPair, AuthLoginResponseDto response) {}
}

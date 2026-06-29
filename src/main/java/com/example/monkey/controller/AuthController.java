package com.example.monkey.controller;

import com.example.monkey.domain.user.LoginAttemptPolicy;
import com.example.monkey.domain.user.PasswordResetChallengeService;
import com.example.monkey.domain.user.SessionTokenService;
import com.example.monkey.domain.user.SessionTokenService.AuthenticatedRefreshToken;
import com.example.monkey.domain.user.SessionTokenService.JwtTokenPair;
import com.example.monkey.domain.user.UserRoles;
import com.example.monkey.dto.AuthLoginResponseDto;
import com.example.monkey.dto.CaptchaConfigResponseDto;
import com.example.monkey.dto.LoginRequestDto;
import com.example.monkey.dto.PasswordResetChallengeRequestDto;
import com.example.monkey.dto.PasswordResetRequestDto;
import com.example.monkey.dto.RegisterRequestDto;
import com.example.monkey.dto.UploadResponseDto;
import com.example.monkey.service.AuditService;
import com.example.monkey.service.AuthResponseService;
import com.example.monkey.service.CaptchaService;
import com.example.monkey.service.FileService;
import com.example.monkey.service.UserService;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.api.Result;
import com.example.monkey.shared.exception.BusinessException;
import com.example.monkey.shared.web.CaptchaHttp;
import com.example.monkey.shared.web.MultipartUploadFile;
import com.example.monkey.shared.web.SessionTokenTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class AuthController {

    static final String PASSWORD_RESET_OTP_REQUIRED = "reset otp required";
    static final String PASSWORD_RESET_OTP_INVALID = "invalid or expired reset otp";
    static final String PASSWORD_RESET_FAILED = "password reset failed";
    static final String ADMIN_MFA_REQUIRED = "admin mfa required";
    static final String ADMIN_MFA_INVALID = "admin mfa invalid";
    static final String LOGIN_CAPTCHA_REQUIRED = "captcha required";
    static final String LOGIN_CAPTCHA_INVALID = "captcha incorrect";
    static final String LOGIN_BAD_CREDENTIALS = "username or password is incorrect";
    static final String REFRESH_TOKEN_NOT_PROVIDED = "refresh token not provided";
    static final String REFRESH_TOKEN_INVALID = "invalid or expired refresh token";
    static final String REGISTRATION_CAPTCHA_INVALID = "captcha incorrect";
    static final String PASSWORD_RESET_CAPTCHA_INVALID = "captcha incorrect";
    static final String REGISTRATION_AVATAR_FAILED = "avatar save failed";
    private static final String ACTION_LOGIN = "login";
    private static final String ACTION_REGISTER = "register";
    private static final String ACTION_PASSWORD_RESET_REQUEST = "password-reset-request";
    private static final String ACTION_PASSWORD_RESET = "password-reset";

    private final UserService userService;
    private final CaptchaService captchaService;
    private final FileService fileService;
    private final SessionTokenService tokenService;
    private final SessionTokenTransport tokenTransport;
    private final LoginAttemptPolicy loginAttemptService;
    private final PasswordResetChallengeService passwordResetOtpService;
    private final AuditService auditService;
    private final AuthResponseService authResponseService;

    public AuthController(
            UserService userService,
            CaptchaService captchaService,
            FileService fileService,
            SessionTokenService tokenService,
            SessionTokenTransport tokenTransport,
            LoginAttemptPolicy loginAttemptService,
            PasswordResetChallengeService passwordResetOtpService,
            AuditService auditService,
            AuthResponseService authResponseService) {
        this.userService = userService;
        this.captchaService = captchaService;
        this.fileService = fileService;
        this.tokenService = tokenService;
        this.tokenTransport = tokenTransport;
        this.loginAttemptService = loginAttemptService;
        this.passwordResetOtpService = passwordResetOtpService;
        this.auditService = auditService;
        this.authResponseService = authResponseService;
    }

    @GetMapping("/captcha")
    @PreAuthorize("permitAll()")
    public void getCaptcha(HttpServletResponse response) throws IOException {
        CaptchaHttp.write(captchaService.createCaptcha(), response);
    }

    @GetMapping("/captcha/config")
    @PreAuthorize("permitAll()")
    public Result<CaptchaConfigResponseDto> getCaptchaConfig() {
        return Result.success(authResponseService.captchaConfig(captchaService.provider(), captchaService.siteKey()));
    }

    @PostMapping("/register")
    @PreAuthorize("permitAll()")
    public Result<Void> register(@Valid @ModelAttribute RegisterRequestDto requestBody, HttpServletRequest request) {
        if (!validateCaptcha(request, requestBody.captcha(), ACTION_REGISTER)) {
            throw authFailure(ErrorCode.VALIDATION_ERROR, REGISTRATION_CAPTCHA_INVALID);
        }

        String avatarPath = null;
        var avatarFile = requestBody.avatarFile();
        if (avatarFile != null && !avatarFile.isEmpty()) {
            try {
                UploadResponseDto uploadResult = fileService.uploadFile(MultipartUploadFile.from(avatarFile), "avatar");
                avatarPath = uploadResult.path();
            } catch (BusinessException exception) {
                throw authFailure(ErrorCode.VALIDATION_ERROR, REGISTRATION_AVATAR_FAILED);
            }
        }
        userService.register(
                requestBody.username(), requestBody.password(), requestBody.phone(), requestBody.email(), avatarPath);
        return Result.success();
    }

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public Result<AuthLoginResponseDto> login(
            @Valid @RequestBody LoginRequestDto requestBody, HttpServletRequest request, HttpServletResponse response) {
        String username = requestBody.username();
        String clientIp = resolveClientIp(request);
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
            String captcha = requestBody.captcha();
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
            if (!validateCaptcha(request, captcha, ACTION_LOGIN)) {
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

        var principal = userService.authenticate(username, requestBody.password());
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
        if (UserRoles.ADMIN.equals(principal.role())) {
            String totpCode = requestBody.totp();
            if (!StringUtils.hasText(totpCode)) {
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
            if (!userService.verifyAdminTotp(principal.userId(), totpCode)) {
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

        loginAttemptService.recordSuccess(username, clientIp);
        rotateSessionIdIfPresent(request);
        auditService.record(
                AuditService.LOGIN_SUCCESS,
                AuditService.OUTCOME_SUCCESS,
                principal.userId(),
                principal.role(),
                username,
                clientIp,
                null);
        JwtTokenPair tokenPair =
                tokenService.issueTokenPair(principal.userId(), principal.role(), principal.authorities());
        tokenTransport.applyTokenCookies(response, tokenPair);
        return Result.success(authResponseService.loginResponse(principal.role(), principal.passwordChangeRequired()));
    }

    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    public Result<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        String clientIp = resolveClientIp(request);
        String refreshToken = tokenTransport.resolveRefreshToken(request);
        if (!StringUtils.hasText(refreshToken)) {
            auditService.record(
                    AuditService.REFRESH_TOKEN_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    null,
                    null,
                    null,
                    clientIp,
                    "missing_refresh_token");
            throw authFailure(ErrorCode.UNAUTHORIZED, REFRESH_TOKEN_NOT_PROVIDED);
        }
        return tokenService
                .parseRefreshToken(refreshToken)
                .map(token -> refreshCurrentPrincipal(token, response, clientIp))
                .orElseGet(() -> rejectInvalidRefreshToken(refreshToken, response, clientIp));
    }

    private Result<Void> rejectInvalidRefreshToken(String refreshToken, HttpServletResponse response, String clientIp) {
        boolean replayDetected = tokenService.revokeUserTokensForRefreshTokenReuse(refreshToken);
        auditService.record(
                replayDetected ? AuditService.REFRESH_TOKEN_REPLAY : AuditService.REFRESH_TOKEN_FAILURE,
                replayDetected ? AuditService.OUTCOME_DENIED : AuditService.OUTCOME_FAILURE,
                null,
                null,
                null,
                clientIp,
                replayDetected ? "replay_detected" : "invalid_refresh_token");
        tokenTransport.clearTokenCookies(response);
        throw authFailure(ErrorCode.UNAUTHORIZED, REFRESH_TOKEN_INVALID);
    }

    private Result<Void> refreshCurrentPrincipal(
            AuthenticatedRefreshToken refreshToken, HttpServletResponse response, String clientIp) {
        return userService
                .currentPrincipal(refreshToken.userId())
                .map(principal -> {
                    JwtTokenPair pair =
                            tokenService.rotateRefreshToken(refreshToken, principal.role(), principal.authorities());
                    tokenTransport.applyTokenCookies(response, pair);
                    auditService.record(
                            AuditService.REFRESH_TOKEN_SUCCESS,
                            AuditService.OUTCOME_SUCCESS,
                            principal.userId(),
                            principal.role(),
                            null,
                            clientIp,
                            null);
                    return Result.success();
                })
                .orElseGet(() -> {
                    tokenService.revokeRefreshToken(refreshToken);
                    auditService.record(
                            AuditService.REFRESH_TOKEN_FAILURE,
                            AuditService.OUTCOME_DENIED,
                            refreshToken.userId(),
                            refreshToken.role(),
                            null,
                            clientIp,
                            "principal_rejected");
                    tokenTransport.clearTokenCookies(response);
                    throw authFailure(ErrorCode.UNAUTHORIZED, REFRESH_TOKEN_INVALID);
                });
    }

    @PostMapping("/reset-password")
    @PreAuthorize("permitAll()")
    public Result<Void> resetPassword(
            @Valid @RequestBody PasswordResetRequestDto requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        String username = requestBody.username();
        String phone = requestBody.phone();
        String email = requestBody.email();
        String otp = requestBody.otp();
        String emailToken = requestBody.emailToken();
        String clientIp = resolveClientIp(request);
        if (captchaService.externalProviderEnabled()
                && !validateCaptcha(request, requestBody.captcha(), ACTION_PASSWORD_RESET)) {
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
        boolean resetChallengeValid = StringUtils.hasText(email) || StringUtils.hasText(emailToken)
                ? passwordResetOtpService.consumeResetChallenge(username, phone, email, otp, emailToken)
                : passwordResetOtpService.consumeResetOtp(username, phone, otp);
        if (!resetChallengeValid) {
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
            userService.resetPasswordAfterOtp(username, phone, requestBody.newPassword());
            userService
                    .findUserIdByUsername(username)
                    .ifPresent(userId -> tokenTransport.revokeUserTokens(userId, response));
            auditService.record(
                    AuditService.PASSWORD_RESET_SUCCESS,
                    AuditService.OUTCOME_SUCCESS,
                    null,
                    null,
                    username,
                    clientIp,
                    null);
            return Result.success();
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

    @PostMapping("/reset-password/request")
    @PreAuthorize("permitAll()")
    public Result<Void> requestPasswordReset(
            @Valid @RequestBody PasswordResetChallengeRequestDto requestBody, HttpServletRequest request) {
        String username = requestBody.username();
        String phone = requestBody.phone();
        String email = requestBody.email();
        String clientIp = resolveClientIp(request);
        if (captchaService.externalProviderEnabled()
                && !validateCaptcha(request, requestBody.captcha(), ACTION_PASSWORD_RESET_REQUEST)) {
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
            passwordResetOtpService.issueResetChallenge(username, phone, email, targetMatches);
            auditService.record(
                    AuditService.PASSWORD_RESET_REQUEST,
                    AuditService.OUTCOME_ACCEPTED,
                    null,
                    null,
                    username,
                    clientIp,
                    "accepted");
            return Result.success();
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

    private static String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean validateCaptcha(HttpServletRequest request, String captcha, String action) {
        return captchaService.validate(CaptchaHttp.challengeId(request), captcha, action, resolveClientIp(request));
    }

    private static void rotateSessionIdIfPresent(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
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
}

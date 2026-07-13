package com.example.monkey.user.interfaces;

import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.web.CaptchaHttp;
import com.example.monkey.shared.interfaces.web.ClientIps;
import com.example.monkey.shared.interfaces.web.MultipartUploadFile;
import com.example.monkey.shared.interfaces.web.SessionTokenTransport;
import com.example.monkey.user.application.AuthResponseService;
import com.example.monkey.user.application.CaptchaService;
import com.example.monkey.user.application.LoginApplicationService;
import com.example.monkey.user.application.PasswordResetApplicationService;
import com.example.monkey.user.application.RefreshTokenApplicationService;
import com.example.monkey.user.application.RefreshTokenApplicationService.RefreshTokenFailure;
import com.example.monkey.user.application.RegistrationApplicationService;
import com.example.monkey.user.application.dto.AuthLoginResponseDto;
import com.example.monkey.user.application.dto.CaptchaConfigResponseDto;
import com.example.monkey.user.application.dto.PasswordPolicyResponseDto;
import com.example.monkey.user.infrastructure.PasswordPolicy;
import com.example.monkey.user.interfaces.dto.LoginRequestDto;
import com.example.monkey.user.interfaces.dto.PasswordResetChallengeRequestDto;
import com.example.monkey.user.interfaces.dto.PasswordResetRequestDto;
import com.example.monkey.user.interfaces.dto.RegisterRequestDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class AuthController {

    private static final String ACTION_LOGIN = "login";
    private static final String ACTION_REGISTER = "register";
    private static final String ACTION_PASSWORD_RESET_REQUEST = "password-reset-request";
    private static final String ACTION_PASSWORD_RESET = "password-reset";

    private final CaptchaService captchaService;
    private final RegistrationApplicationService registrationService;
    private final LoginApplicationService loginService;
    private final RefreshTokenApplicationService refreshTokenService;
    private final SessionTokenTransport tokenTransport;
    private final PasswordResetApplicationService passwordResetService;
    private final AuthResponseService authResponseService;
    private final PasswordPolicy passwordPolicy;

    public AuthController(
            CaptchaService captchaService,
            RegistrationApplicationService registrationService,
            LoginApplicationService loginService,
            RefreshTokenApplicationService refreshTokenService,
            SessionTokenTransport tokenTransport,
            PasswordResetApplicationService passwordResetService,
            AuthResponseService authResponseService,
            PasswordPolicy passwordPolicy) {
        this.captchaService = captchaService;
        this.registrationService = registrationService;
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
        this.tokenTransport = tokenTransport;
        this.passwordResetService = passwordResetService;
        this.authResponseService = authResponseService;
        this.passwordPolicy = passwordPolicy;
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

    @GetMapping("/password-policy")
    @PreAuthorize("permitAll()")
    public Result<PasswordPolicyResponseDto> passwordPolicy() {
        return Result.success(passwordPolicy.metadata());
    }

    @PostMapping("/register")
    @PreAuthorize("permitAll()")
    public Result<Void> register(@Valid @ModelAttribute RegisterRequestDto requestBody, HttpServletRequest request) {
        var avatarFile = requestBody.avatarFile();
        registrationService.register(
                requestBody.username(),
                requestBody.password(),
                requestBody.phone(),
                requestBody.email(),
                CaptchaHttp.challengeId(request),
                requestBody.captcha(),
                ClientIps.resolve(request),
                avatarFile != null && !avatarFile.isEmpty() ? MultipartUploadFile.from(avatarFile) : null);
        return Result.success();
    }

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public Result<AuthLoginResponseDto> login(
            @Valid @RequestBody LoginRequestDto requestBody, HttpServletRequest request, HttpServletResponse response) {
        var result = loginService.login(
                requestBody.username(),
                requestBody.password(),
                requestBody.captcha(),
                requestBody.totp(),
                CaptchaHttp.challengeId(request),
                ClientIps.resolve(request));
        rotateSessionIdIfPresent(request);
        tokenTransport.applyTokenCookies(response, result.tokenPair());
        return Result.success(result.response());
    }

    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    public Result<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = tokenTransport.resolveRefreshToken(request);
        try {
            var result = refreshTokenService.refresh(refreshToken, ClientIps.resolve(request));
            tokenTransport.applyTokenCookies(response, result.tokenPair());
            return Result.success();
        } catch (RefreshTokenFailure exception) {
            if (exception.clearTokenCookies()) {
                tokenTransport.clearTokenCookies(response);
            }
            throw exception;
        }
    }

    @PostMapping("/reset-password")
    @PreAuthorize("permitAll()")
    public Result<Void> resetPassword(
            @Valid @RequestBody PasswordResetRequestDto requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        var result = passwordResetService.resetPassword(
                requestBody.username(),
                requestBody.phone(),
                requestBody.email(),
                requestBody.otp(),
                requestBody.emailToken(),
                requestBody.newPassword(),
                requestBody.captcha(),
                CaptchaHttp.challengeId(request),
                ClientIps.resolve(request));
        if (result.userIdToRevoke() != null) {
            tokenTransport.revokeUserTokens(result.userIdToRevoke(), response);
        }
        return Result.success();
    }

    @PostMapping("/reset-password/request")
    @PreAuthorize("permitAll()")
    public Result<Void> requestPasswordReset(
            @Valid @RequestBody PasswordResetChallengeRequestDto requestBody, HttpServletRequest request) {
        passwordResetService.requestPasswordReset(
                requestBody.username(),
                requestBody.phone(),
                requestBody.email(),
                requestBody.captcha(),
                CaptchaHttp.challengeId(request),
                ClientIps.resolve(request));
        return Result.success();
    }

    private static void rotateSessionIdIfPresent(HttpServletRequest request) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
    }
}

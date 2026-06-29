package com.example.monkey.controller;

import static com.example.monkey.domain.user.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.domain.user.SessionUser;
import com.example.monkey.dto.PasswordChangeRequestDto;
import com.example.monkey.dto.UserAvatarRequestDto;
import com.example.monkey.dto.UserProfileResponseDto;
import com.example.monkey.service.AuditService;
import com.example.monkey.service.CaptchaService;
import com.example.monkey.service.UserService;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.api.Result;
import com.example.monkey.shared.exception.BusinessException;
import com.example.monkey.shared.web.CaptchaHttp;
import com.example.monkey.shared.web.SessionTokenTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/user", "/api/v1/users"})
public class UserController {
    private static final String ACTION_CHANGE_PASSWORD = "change-password";

    private final UserService userService;
    private final CaptchaService captchaService;
    private final AuditService auditService;
    private final SessionTokenTransport tokenTransport;

    public UserController(
            UserService userService,
            CaptchaService captchaService,
            AuditService auditService,
            SessionTokenTransport tokenTransport) {
        this.userService = userService;
        this.captchaService = captchaService;
        this.auditService = auditService;
        this.tokenTransport = tokenTransport;
    }

    @GetMapping("/captcha")
    @PreAuthorize("hasAuthority('USER_PROFILE_WRITE')")
    public void getCaptcha(HttpServletResponse response) throws IOException {
        CaptchaHttp.write(captchaService.createCaptcha(), response);
    }

    @GetMapping("/me")
    @PreAuthorize("permitAll()")
    public Result<UserProfileResponseDto> getCurrentUser(@AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(userService.getUserInfo(currentUser, false));
    }

    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('USER_PROFILE_READ')")
    public Result<UserProfileResponseDto> getProfile(@AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(userService.getUserInfo(currentUser, true));
    }

    @PostMapping("/update-avatar")
    @PreAuthorize("hasAuthority('USER_PROFILE_WRITE')")
    public Result<Void> updateAvatar(
            @Valid @RequestBody UserAvatarRequestDto requestBody, @AuthenticationPrincipal SessionUser currentUser) {
        Long userId = requireUserId(currentUser);
        userService.updateAvatar(userId, requestBody.avatarPath());
        return Result.success();
    }

    @PostMapping("/update-password")
    @PreAuthorize("hasAuthority('USER_PROFILE_WRITE')")
    public Result<Void> updatePassword(
            @Valid @RequestBody PasswordChangeRequestDto requestBody,
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal SessionUser currentUser) {
        Long userId = requireUserId(currentUser);

        if (!captchaService.validate(
                CaptchaHttp.challengeId(request),
                requestBody.captcha(),
                ACTION_CHANGE_PASSWORD,
                resolveClientIp(request))) {
            auditService.record(
                    AuditService.PASSWORD_CHANGE_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    userId,
                    currentUser.role(),
                    null,
                    resolveClientIp(request),
                    "captcha_invalid");
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "captcha incorrect");
        }

        try {
            userService.updatePassword(userId, requestBody.phone(), requestBody.newPassword(), null);
            auditService.record(
                    AuditService.PASSWORD_CHANGE_SUCCESS,
                    AuditService.OUTCOME_SUCCESS,
                    userId,
                    currentUser.role(),
                    null,
                    resolveClientIp(request),
                    null);
            tokenTransport.revokeUserTokens(userId, response);
            return Result.success();
        } catch (BusinessException exception) {
            auditService.record(
                    AuditService.PASSWORD_CHANGE_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    userId,
                    currentUser.role(),
                    null,
                    resolveClientIp(request),
                    auditDetail(exception.getMessage()));
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

    private static String auditDetail(String result) {
        if (!StringUtils.hasText(result)) {
            return "unknown";
        }
        return result.replace(':', '_');
    }
}

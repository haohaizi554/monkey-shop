package com.example.monkey.user.interfaces;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.web.CaptchaHttp;
import com.example.monkey.shared.interfaces.web.ClientIps;
import com.example.monkey.shared.interfaces.web.SessionTokenTransport;
import com.example.monkey.user.application.CaptchaService;
import com.example.monkey.user.application.PasswordChangeApplicationService;
import com.example.monkey.user.application.UserProfileApplicationService;
import com.example.monkey.user.application.dto.UserProfileResponseDto;
import com.example.monkey.user.interfaces.dto.PasswordChangeRequestDto;
import com.example.monkey.user.interfaces.dto.UserAvatarRequestDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/user", "/api/v1/users"})
public class UserController {

    private final UserProfileApplicationService userProfileService;
    private final CaptchaService captchaService;
    private final PasswordChangeApplicationService passwordChangeService;
    private final SessionTokenTransport tokenTransport;

    public UserController(
            UserProfileApplicationService userProfileService,
            CaptchaService captchaService,
            PasswordChangeApplicationService passwordChangeService,
            SessionTokenTransport tokenTransport) {
        this.userProfileService = userProfileService;
        this.captchaService = captchaService;
        this.passwordChangeService = passwordChangeService;
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
        return Result.success(userProfileService.currentUser(currentUser));
    }

    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('USER_PROFILE_READ')")
    public Result<UserProfileResponseDto> getProfile(@AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(userProfileService.profile(currentUser));
    }

    @PostMapping("/update-avatar")
    @PreAuthorize("hasAuthority('USER_PROFILE_WRITE')")
    public Result<Void> updateAvatar(
            @Valid @RequestBody UserAvatarRequestDto requestBody, @AuthenticationPrincipal SessionUser currentUser) {
        userProfileService.updateAvatar(currentUser, requestBody.avatarPath());
        return Result.success();
    }

    @PostMapping("/update-password")
    @PreAuthorize("hasAuthority('USER_PROFILE_WRITE')")
    public Result<Void> updatePassword(
            @Valid @RequestBody PasswordChangeRequestDto requestBody,
            HttpServletRequest request,
            HttpServletResponse response,
            @AuthenticationPrincipal SessionUser currentUser) {
        var result = passwordChangeService.changePassword(
                currentUser,
                CaptchaHttp.challengeId(request),
                requestBody.captcha(),
                requestBody.phone(),
                requestBody.newPassword(),
                ClientIps.resolve(request));
        tokenTransport.revokeUserTokens(result.userIdToRevoke(), response);
        return Result.success();
    }
}

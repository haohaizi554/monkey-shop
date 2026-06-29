package com.example.monkey.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.storage.UploadFile;
import com.example.monkey.domain.user.AuthPrincipal;
import com.example.monkey.domain.user.LoginAttemptPolicy;
import com.example.monkey.domain.user.PasswordResetChallengeService;
import com.example.monkey.domain.user.SessionTokenService;
import com.example.monkey.domain.user.SessionTokenService.AuthenticatedRefreshToken;
import com.example.monkey.domain.user.SessionTokenService.JwtTokenPair;
import com.example.monkey.dto.AuthLoginResponseDto;
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
import com.example.monkey.shared.web.SessionTokenTransport;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    private static final String CAPTCHA_CHALLENGE_ID = "challenge-id";

    @Mock
    private UserService userService;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private FileService fileService;

    @Mock
    private SessionTokenService tokenService;

    @Mock
    private SessionTokenTransport tokenTransport;

    @Mock
    private LoginAttemptPolicy loginAttemptService;

    @Mock
    private PasswordResetChallengeService passwordResetOtpService;

    @Mock
    private AuditService auditService;

    @Test
    void registerRejectsInvalidCaptchaBeforeAvatarUploadOrUserCreation() {
        AuthController controller = newController();
        MockHttpServletRequest request = requestWithCaptcha();
        when(captchaService.validate(CAPTCHA_CHALLENGE_ID, "bad", "register", "127.0.0.1"))
                .thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.register(
                        register("alice", "StrongPass1!", "18888888888", "alice@example.com", "bad", null), request))
                .withMessage(AuthController.REGISTRATION_CAPTCHA_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(fileService, userService);
    }

    @Test
    void registerAcceptsValidatedDtoAndUploadsOptionalAvatar() {
        AuthController controller = newController();
        MockHttpServletRequest request = requestWithCaptcha();
        MockMultipartFile avatar = new MockMultipartFile("avatarFile", "avatar.png", "image/png", new byte[] {1, 2, 3});
        when(captchaService.validate(CAPTCHA_CHALLENGE_ID, "1234", "register", "127.0.0.1"))
                .thenReturn(true);
        when(fileService.uploadFile(any(UploadFile.class), eq("avatar")))
                .thenReturn(new UploadResponseDto("/images/avatar.png", false));

        Result<Void> result = controller.register(
                register("alice", "StrongPass1!", "18888888888", "alice@example.com", "1234", avatar), request);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(userService).register("alice", "StrongPass1!", "18888888888", "alice@example.com", "/images/avatar.png");
    }

    @Test
    void resetPasswordRequestIssuesOtpOnlyWhenTargetMatchesButReturnsResultEnvelope() {
        AuthController controller = newController();
        when(userService.passwordResetTargetMatches("alice", "18888888888", null))
                .thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        Result<Void> result = controller.requestPasswordReset(resetChallenge("alice", "18888888888", null), request);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(passwordResetOtpService).issueResetChallenge("alice", "18888888888", null, true);
        verifyNoInteractions(fileService);
    }

    @Test
    void resetPasswordRequestIssuesDualChannelChallengeWhenEmailMatches() {
        AuthController controller = newController();
        when(userService.passwordResetTargetMatches("alice", "18888888888", "alice@example.com"))
                .thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        Result<Void> result =
                controller.requestPasswordReset(resetChallenge("alice", "18888888888", "alice@example.com"), request);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(passwordResetOtpService).issueResetChallenge("alice", "18888888888", "alice@example.com", true);
    }

    @Test
    void resetPasswordRequestPropagatesRateLimitExceptionFromChallengeIssuance() {
        AuthController controller = newController();
        when(userService.passwordResetTargetMatches("alice", "18888888888", null))
                .thenReturn(true);
        doThrow(new BusinessException(ErrorCode.RATE_LIMIT, "too many reset requests"))
                .when(passwordResetOtpService)
                .issueResetChallenge("alice", "18888888888", null, true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(
                        () -> controller.requestPasswordReset(resetChallenge("alice", "18888888888", null), request))
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
    void resetPasswordRequiresValidOtpBeforeChangingPassword() {
        AuthController controller = newController();
        when(passwordResetOtpService.consumeResetOtp("alice", "18888888888", "654321"))
                .thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.resetPassword(
                        resetPassword("alice", "18888888888", null, "654321", null, "StrongPass1!"),
                        request,
                        new MockHttpServletResponse()))
                .withMessage(AuthController.PASSWORD_RESET_OTP_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(userService, never()).resetPasswordAfterOtp("alice", "18888888888", "StrongPass1!");
    }

    @Test
    void resetPasswordUpdatesPasswordAfterOtpIsConsumed() {
        AuthController controller = newController();
        when(passwordResetOtpService.consumeResetOtp("alice", "18888888888", "654321"))
                .thenReturn(true);
        when(userService.findUserIdByUsername("alice")).thenReturn(Optional.of(7L));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Result<Void> result = controller.resetPassword(
                resetPassword("alice", "18888888888", null, "654321", null, "StrongPass1!"), request, response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(tokenTransport).revokeUserTokens(7L, response);
    }

    @Test
    void resetPasswordDoesNotExposeMissingUserAfterOtpIsConsumed() {
        AuthController controller = newController();
        when(passwordResetOtpService.consumeResetOtp("alice", "18888888888", "654321"))
                .thenReturn(true);
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "user not found"))
                .when(userService)
                .resetPasswordAfterOtp("alice", "18888888888", "StrongPass1!");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.resetPassword(
                        resetPassword("alice", "18888888888", null, "654321", null, "StrongPass1!"), request, response))
                .withMessage(AuthController.PASSWORD_RESET_FAILED)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(tokenTransport, never()).revokeUserTokens(7L, response);
    }

    @Test
    void resetPasswordReturnsPasswordPolicyErrorsAfterOtpIsConsumed() {
        AuthController controller = newController();
        when(passwordResetOtpService.consumeResetOtp("alice", "18888888888", "654321"))
                .thenReturn(true);
        doThrow(new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "password policy violation: password must contain a special character"))
                .when(userService)
                .resetPasswordAfterOtp("alice", "18888888888", "Password1");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.resetPassword(
                        resetPassword("alice", "18888888888", null, "654321", null, "Password1"),
                        request,
                        new MockHttpServletResponse()))
                .withMessage("password policy violation: password must contain a special character")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void resetPasswordAcceptsDualChannelEmailTokenChallenge() {
        AuthController controller = newController();
        when(passwordResetOtpService.consumeResetChallenge(
                        "alice", "18888888888", "alice@example.com", "654321", "email-token"))
                .thenReturn(true);
        when(userService.findUserIdByUsername("alice")).thenReturn(Optional.of(7L));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Result<Void> result = controller.resetPassword(
                resetPassword("alice", "18888888888", "alice@example.com", "654321", "email-token", "StrongPass1!"),
                request,
                response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(tokenTransport).revokeUserTokens(7L, response);
    }

    @Test
    void loginDeniedByRateLimiterDoesNotAuthenticateOrIssueTokens() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doThrow(new BusinessException(ErrorCode.RATE_LIMIT, "too many login attempts"))
                .when(loginAttemptService)
                .enforceAllowed("alice", "127.0.0.1");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.login(login("alice", "bad-password"), request, response))
                .withMessage("too many login attempts")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT));
        verifyNoInteractions(userService, tokenService);
    }

    @Test
    void failedLoginRecordsFailureWithoutIssuingTokens() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(userService.authenticate("alice", "bad-password")).thenReturn(null);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.login(login("alice", "bad-password"), request, response))
                .withMessage(AuthController.LOGIN_BAD_CREDENTIALS)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(loginAttemptService).recordFailure("alice", "127.0.0.1");
        verify(loginAttemptService, never()).recordSuccess("alice", "127.0.0.1");
        verifyNoInteractions(tokenService);
    }

    @Test
    void loginRequiresCaptchaWhenRepeatedFailuresCrossThreshold() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(loginAttemptService.requiresCaptcha("alice", "127.0.0.1")).thenReturn(true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.login(login("alice", "StrongPass1!"), request, response))
                .withMessage(AuthController.LOGIN_CAPTCHA_REQUIRED)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(userService, tokenService);
    }

    @Test
    void loginRejectsInvalidCaptchaBeforeAuthentication() {
        AuthController controller = newController();
        MockHttpServletRequest request = requestWithCaptcha();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(loginAttemptService.requiresCaptcha("alice", "127.0.0.1")).thenReturn(true);
        when(captchaService.validate(CAPTCHA_CHALLENGE_ID, "bad", "login", "127.0.0.1"))
                .thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.login(login("alice", "StrongPass1!", "bad", null), request, response))
                .withMessage(AuthController.LOGIN_CAPTCHA_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(loginAttemptService).recordFailure("alice", "127.0.0.1");
        verifyNoInteractions(userService, tokenService);
    }

    @Test
    void loginAuthenticatesAfterRequiredCaptchaPasses() {
        AuthController controller = newController();
        MockHttpServletRequest request = requestWithCaptcha();
        MockHttpServletResponse response = new MockHttpServletResponse();
        JwtTokenPair tokenPair =
                new JwtTokenPair("access-token", "refresh-token", "access-id", "refresh-id", 900, 604800);
        when(loginAttemptService.requiresCaptcha("alice", "127.0.0.1")).thenReturn(true);
        when(captchaService.validate(CAPTCHA_CHALLENGE_ID, "1234", "login", "127.0.0.1"))
                .thenReturn(true);
        when(userService.authenticate("alice", "StrongPass1!")).thenReturn(new AuthPrincipal(7L, "USER"));
        when(tokenService.issueTokenPair(7L, "USER", List.of("ROLE_USER"))).thenReturn(tokenPair);

        Result<AuthLoginResponseDto> result =
                controller.login(login("alice", "StrongPass1!", "1234", null), request, response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data().role()).isEqualTo("USER");
        assertThat(result.data().passwordChangeRequired()).isFalse();
        verify(loginAttemptService).recordSuccess("alice", "127.0.0.1");
        verify(tokenTransport).applyTokenCookies(response, tokenPair);
    }

    @Test
    void successfulLoginResetsAttemptsAndIssuesJwtCookies() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 10.0.0.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        JwtTokenPair tokenPair =
                new JwtTokenPair("access-token", "refresh-token", "access-id", "refresh-id", 900, 604800);
        when(userService.authenticate("alice", "StrongPass1!")).thenReturn(new AuthPrincipal(7L, "USER"));
        when(tokenService.issueTokenPair(7L, "USER", List.of("ROLE_USER"))).thenReturn(tokenPair);

        Result<AuthLoginResponseDto> result = controller.login(login("alice", "StrongPass1!"), request, response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data().role()).isEqualTo("USER");
        assertThat(result.data().passwordChangeRequired()).isFalse();
        verify(loginAttemptService).recordSuccess("alice", "203.0.113.10");
        verify(tokenTransport).applyTokenCookies(response, tokenPair);
    }

    @Test
    void successfulLoginIncludesPasswordChangeRequirementFlag() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        JwtTokenPair tokenPair =
                new JwtTokenPair("access-token", "refresh-token", "access-id", "refresh-id", 900, 604800);
        AuthPrincipal principal = new AuthPrincipal(7L, "USER", List.of("ROLE_USER"), true);
        when(userService.authenticate("alice", "StrongPass1!")).thenReturn(principal);
        when(tokenService.issueTokenPair(7L, "USER", List.of("ROLE_USER"))).thenReturn(tokenPair);

        Result<AuthLoginResponseDto> result = controller.login(login("alice", "StrongPass1!"), request, response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data().role()).isEqualTo("USER");
        assertThat(result.data().passwordChangeRequired()).isTrue();
        verify(tokenTransport).applyTokenCookies(response, tokenPair);
    }

    @Test
    void successfulLoginRotatesExistingSessionId() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        String originalSessionId = request.getSession(true).getId();
        MockHttpServletResponse response = new MockHttpServletResponse();
        JwtTokenPair tokenPair =
                new JwtTokenPair("access-token", "refresh-token", "access-id", "refresh-id", 900, 604800);
        when(userService.authenticate("alice", "StrongPass1!")).thenReturn(new AuthPrincipal(7L, "USER"));
        when(tokenService.issueTokenPair(7L, "USER", List.of("ROLE_USER"))).thenReturn(tokenPair);

        Result<AuthLoginResponseDto> result = controller.login(login("alice", "StrongPass1!"), request, response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data().role()).isEqualTo("USER");
        assertThat(request.getSession(false).getId()).isNotEqualTo(originalSessionId);
    }

    @Test
    void adminLoginRequiresTotpBeforeIssuingJwtCookies() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(userService.authenticate("admin", "StrongPass1!")).thenReturn(new AuthPrincipal(1L, "ADMIN"));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.login(login("admin", "StrongPass1!"), request, response))
                .withMessage(AuthController.ADMIN_MFA_REQUIRED)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(loginAttemptService).recordFailure("admin", "127.0.0.1");
        verifyNoInteractions(tokenService);
    }

    @Test
    void adminLoginRejectsInvalidTotpWithoutIssuingJwtCookies() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(userService.authenticate("admin", "StrongPass1!")).thenReturn(new AuthPrincipal(1L, "ADMIN"));
        when(userService.verifyAdminTotp(1L, "000000")).thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.login(login("admin", "StrongPass1!", null, "000000"), request, response))
                .withMessage(AuthController.ADMIN_MFA_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(loginAttemptService).recordFailure("admin", "127.0.0.1");
        verifyNoInteractions(tokenService);
    }

    @Test
    void adminLoginIssuesJwtCookiesAfterValidTotp() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        JwtTokenPair tokenPair =
                new JwtTokenPair("access-token", "refresh-token", "access-id", "refresh-id", 900, 604800);
        when(userService.authenticate("admin", "StrongPass1!")).thenReturn(new AuthPrincipal(1L, "ADMIN"));
        when(userService.verifyAdminTotp(1L, "654321")).thenReturn(true);
        when(tokenService.issueTokenPair(1L, "ADMIN", List.of("ROLE_ADMIN"))).thenReturn(tokenPair);

        Result<AuthLoginResponseDto> result =
                controller.login(login("admin", "StrongPass1!", null, "654321"), request, response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data().role()).isEqualTo("ADMIN");
        assertThat(result.data().passwordChangeRequired()).isFalse();
        verify(loginAttemptService).recordSuccess("admin", "127.0.0.1");
        verify(tokenTransport).applyTokenCookies(response, tokenPair);
    }

    @Test
    void refreshReissuesTokenPairWithCurrentPrincipalAuthorities() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticatedRefreshToken refreshToken = new AuthenticatedRefreshToken(
                7L,
                "USER",
                List.of("ROLE_USER", "ORDER_CREATE"),
                "refresh-id",
                Instant.now().plusSeconds(60));
        JwtTokenPair tokenPair =
                new JwtTokenPair("access-token", "refresh-token", "access-id", "new-refresh-id", 900, 604800);
        AuthPrincipal currentPrincipal = new AuthPrincipal(7L, "USER", List.of("ROLE_USER", "ORDER_READ_OWN"));
        when(tokenTransport.resolveRefreshToken(request)).thenReturn("old-refresh-token");
        when(tokenService.parseRefreshToken("old-refresh-token")).thenReturn(Optional.of(refreshToken));
        when(userService.currentPrincipal(7L)).thenReturn(Optional.of(currentPrincipal));
        when(tokenService.rotateRefreshToken(refreshToken, "USER", currentPrincipal.authorities()))
                .thenReturn(tokenPair);

        Result<Void> result = controller.refresh(request, response);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(tokenTransport).applyTokenCookies(response, tokenPair);
        verify(auditService)
                .record(
                        AuditService.REFRESH_TOKEN_SUCCESS,
                        AuditService.OUTCOME_SUCCESS,
                        7L,
                        "USER",
                        null,
                        "203.0.113.10",
                        null);
    }

    @Test
    void refreshRevokesRefreshTokenWhenCurrentPrincipalIsRejected() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.11");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticatedRefreshToken refreshToken = new AuthenticatedRefreshToken(
                7L, "USER", List.of("ROLE_USER"), "refresh-id", Instant.now().plusSeconds(60));
        when(tokenTransport.resolveRefreshToken(request)).thenReturn("old-refresh-token");
        when(tokenService.parseRefreshToken("old-refresh-token")).thenReturn(Optional.of(refreshToken));
        when(userService.currentPrincipal(7L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.refresh(request, response))
                .withMessage(AuthController.REFRESH_TOKEN_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(tokenService).revokeRefreshToken(refreshToken);
        verify(tokenTransport).clearTokenCookies(response);
        verify(auditService)
                .record(
                        AuditService.REFRESH_TOKEN_FAILURE,
                        AuditService.OUTCOME_DENIED,
                        7L,
                        "USER",
                        null,
                        "203.0.113.11",
                        "principal_rejected");
        verify(tokenService, never())
                .rotateRefreshToken(any(AuthenticatedRefreshToken.class), any(String.class), any());
        verify(tokenTransport, never()).applyTokenCookies(any(), any(JwtTokenPair.class));
    }

    @Test
    void refreshReplayRevokesTokenSubjectAndClearsCookies() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.12");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenTransport.resolveRefreshToken(request)).thenReturn("replayed-refresh-token");
        when(tokenService.parseRefreshToken("replayed-refresh-token")).thenReturn(Optional.empty());
        when(tokenService.revokeUserTokensForRefreshTokenReuse("replayed-refresh-token"))
                .thenReturn(true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.refresh(request, response))
                .withMessage(AuthController.REFRESH_TOKEN_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(tokenService).revokeUserTokensForRefreshTokenReuse("replayed-refresh-token");
        verify(tokenTransport).clearTokenCookies(response);
        verify(auditService)
                .record(
                        AuditService.REFRESH_TOKEN_REPLAY,
                        AuditService.OUTCOME_DENIED,
                        null,
                        null,
                        null,
                        "203.0.113.12",
                        "replay_detected");
        verify(tokenService, never())
                .rotateRefreshToken(any(AuthenticatedRefreshToken.class), any(String.class), any());
        verify(tokenTransport, never()).applyTokenCookies(any(), any(JwtTokenPair.class));
    }

    @Test
    void refreshInvalidTokenClearsCookiesAndAuditsFailure() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.14");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(tokenTransport.resolveRefreshToken(request)).thenReturn("tampered-refresh-token");
        when(tokenService.parseRefreshToken("tampered-refresh-token")).thenReturn(Optional.empty());
        when(tokenService.revokeUserTokensForRefreshTokenReuse("tampered-refresh-token"))
                .thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.refresh(request, response))
                .withMessage(AuthController.REFRESH_TOKEN_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(tokenService).revokeUserTokensForRefreshTokenReuse("tampered-refresh-token");
        verify(tokenTransport).clearTokenCookies(response);
        verify(auditService)
                .record(
                        AuditService.REFRESH_TOKEN_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        null,
                        null,
                        null,
                        "203.0.113.14",
                        "invalid_refresh_token");
        verify(tokenService, never())
                .rotateRefreshToken(any(AuthenticatedRefreshToken.class), any(String.class), any());
        verify(tokenTransport, never()).applyTokenCookies(any(), any(JwtTokenPair.class));
    }

    @Test
    void refreshAuditsMissingRefreshTokenWithoutClearingCookies() {
        AuthController controller = newController();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.13");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.refresh(request, response))
                .withMessage(AuthController.REFRESH_TOKEN_NOT_PROVIDED)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        verify(auditService)
                .record(
                        AuditService.REFRESH_TOKEN_FAILURE,
                        AuditService.OUTCOME_FAILURE,
                        null,
                        null,
                        null,
                        "203.0.113.13",
                        "missing_refresh_token");
        verify(tokenTransport, never()).clearTokenCookies(any());
        verify(tokenService, never()).parseRefreshToken(any());
    }

    private static PasswordResetChallengeRequestDto resetChallenge(String username, String phone, String email) {
        return new PasswordResetChallengeRequestDto(username, phone, email, null);
    }

    private static PasswordResetRequestDto resetPassword(
            String username, String phone, String email, String otp, String emailToken, String newPassword) {
        return new PasswordResetRequestDto(username, phone, email, otp, emailToken, newPassword, null);
    }

    private static LoginRequestDto login(String username, String password) {
        return login(username, password, null, null);
    }

    private static LoginRequestDto login(String username, String password, String captcha, String totp) {
        return new LoginRequestDto(username, password, captcha, totp);
    }

    private static MockHttpServletRequest requestWithCaptcha() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setCookies(new Cookie(CaptchaHttp.CAPTCHA_ID_COOKIE, CAPTCHA_CHALLENGE_ID));
        return request;
    }

    private static RegisterRequestDto register(
            String username, String password, String phone, String email, String captcha, MultipartFile avatarFile) {
        return new RegisterRequestDto(username, password, phone, email, captcha, avatarFile);
    }

    private AuthController newController() {
        return new AuthController(
                userService,
                captchaService,
                fileService,
                tokenService,
                tokenTransport,
                loginAttemptService,
                passwordResetOtpService,
                auditService,
                new AuthResponseService());
    }
}

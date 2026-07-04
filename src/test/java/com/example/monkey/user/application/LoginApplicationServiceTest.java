package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionTokenPair;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoginApplicationServiceTest {

    private static final String CAPTCHA_CHALLENGE_ID = "challenge-id";

    @Mock
    private AuthenticationApplicationService authenticationService;

    @Mock
    private CaptchaService captchaService;

    @Mock
    private LoginAttemptApplicationService loginAttemptService;

    @Mock
    private SessionTokenApplicationService tokenService;

    @Mock
    private AuditService auditService;

    private LoginApplicationService loginService;

    @BeforeEach
    void setUp() {
        loginService = new LoginApplicationService(
                authenticationService,
                captchaService,
                loginAttemptService,
                tokenService,
                auditService,
                new AuthResponseService());
    }

    @Test
    void loginDeniedByRateLimiterDoesNotAuthenticateOrIssueTokens() {
        doThrow(new BusinessException(ErrorCode.RATE_LIMIT, "too many login attempts"))
                .when(loginAttemptService)
                .enforceAllowed("alice", "127.0.0.1");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> loginService.login("alice", "bad-password", null, null, null, "127.0.0.1"))
                .withMessage("too many login attempts")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.RATE_LIMIT));

        verify(auditService)
                .record(
                        AuditService.LOGIN_RATE_LIMITED,
                        AuditService.OUTCOME_DENIED,
                        null,
                        null,
                        "alice",
                        "127.0.0.1",
                        "rate_limit");
        verifyNoInteractions(authenticationService, tokenService);
    }

    @Test
    void failedLoginRecordsFailureWithoutIssuingTokens() {
        when(authenticationService.authenticate("alice", "bad-password")).thenReturn(null);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> loginService.login("alice", "bad-password", null, null, null, "127.0.0.1"))
                .withMessage(LoginApplicationService.LOGIN_BAD_CREDENTIALS)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(loginAttemptService).recordFailure("alice", "127.0.0.1");
        verify(loginAttemptService, never()).recordSuccess("alice", "127.0.0.1");
        verifyNoInteractions(tokenService);
    }

    @Test
    void loginRequiresCaptchaWhenRepeatedFailuresCrossThreshold() {
        when(loginAttemptService.requiresCaptcha("alice", "127.0.0.1")).thenReturn(true);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> loginService.login("alice", "StrongPass1!", null, null, null, "127.0.0.1"))
                .withMessage(LoginApplicationService.LOGIN_CAPTCHA_REQUIRED)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verifyNoInteractions(authenticationService, tokenService);
    }

    @Test
    void loginRejectsInvalidCaptchaBeforeAuthentication() {
        when(loginAttemptService.requiresCaptcha("alice", "127.0.0.1")).thenReturn(true);
        when(captchaService.validate(CAPTCHA_CHALLENGE_ID, "bad", "login", "127.0.0.1"))
                .thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() ->
                        loginService.login("alice", "StrongPass1!", "bad", null, CAPTCHA_CHALLENGE_ID, "127.0.0.1"))
                .withMessage(LoginApplicationService.LOGIN_CAPTCHA_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

        verify(loginAttemptService).recordFailure("alice", "127.0.0.1");
        verifyNoInteractions(authenticationService, tokenService);
    }

    @Test
    void loginAuthenticatesAfterRequiredCaptchaPasses() {
        SessionTokenPair tokenPair =
                new SessionTokenPair("access-token", "refresh-token", "access-id", "refresh-id", 900, 604800);
        when(loginAttemptService.requiresCaptcha("alice", "127.0.0.1")).thenReturn(true);
        when(captchaService.validate(CAPTCHA_CHALLENGE_ID, "1234", "login", "127.0.0.1"))
                .thenReturn(true);
        when(authenticationService.authenticate("alice", "StrongPass1!"))
                .thenReturn(new AuthenticatedUserPrincipal(7L, "USER", List.of("ROLE_USER"), false, 200L));
        when(tokenService.issueTokenPair(7L, "USER", List.of("ROLE_USER"), 200L))
                .thenReturn(tokenPair);

        var result = loginService.login("alice", "StrongPass1!", "1234", null, CAPTCHA_CHALLENGE_ID, "127.0.0.1");

        assertThat(result.tokenPair()).isSameAs(tokenPair);
        assertThat(result.response().role()).isEqualTo("USER");
        assertThat(result.response().passwordChangeRequired()).isFalse();
        verify(loginAttemptService).recordSuccess("alice", "127.0.0.1");
    }

    @Test
    void successfulLoginIncludesPasswordChangeRequirementFlag() {
        SessionTokenPair tokenPair =
                new SessionTokenPair("access-token", "refresh-token", "access-id", "refresh-id", 900, 604800);
        AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(7L, "USER", List.of("ROLE_USER"), true);
        when(authenticationService.authenticate("alice", "StrongPass1!")).thenReturn(principal);
        when(tokenService.issueTokenPair(7L, "USER", List.of("ROLE_USER"), 1L)).thenReturn(tokenPair);

        var result = loginService.login("alice", "StrongPass1!", null, null, null, "203.0.113.10");

        assertThat(result.tokenPair()).isSameAs(tokenPair);
        assertThat(result.response().role()).isEqualTo("USER");
        assertThat(result.response().passwordChangeRequired()).isTrue();
        verify(loginAttemptService).recordSuccess("alice", "203.0.113.10");
    }

    @Test
    void adminLoginRequiresTotpBeforeIssuingJwtCookies() {
        when(authenticationService.authenticate("admin", "StrongPass1!"))
                .thenReturn(new AuthenticatedUserPrincipal(1L, "ADMIN"));

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> loginService.login("admin", "StrongPass1!", null, null, null, "127.0.0.1"))
                .withMessage(LoginApplicationService.ADMIN_MFA_REQUIRED)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(loginAttemptService).recordFailure("admin", "127.0.0.1");
        verifyNoInteractions(tokenService);
    }

    @Test
    void adminLoginRejectsInvalidTotpWithoutIssuingJwtCookies() {
        when(authenticationService.authenticate("admin", "StrongPass1!"))
                .thenReturn(new AuthenticatedUserPrincipal(1L, "ADMIN"));
        when(authenticationService.verifyAdminTotp(1L, "000000")).thenReturn(false);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> loginService.login("admin", "StrongPass1!", null, "000000", null, "127.0.0.1"))
                .withMessage(LoginApplicationService.ADMIN_MFA_INVALID)
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(loginAttemptService).recordFailure("admin", "127.0.0.1");
        verifyNoInteractions(tokenService);
    }

    @Test
    void adminLoginIssuesJwtCookiesAfterValidTotp() {
        SessionTokenPair tokenPair =
                new SessionTokenPair("access-token", "refresh-token", "access-id", "refresh-id", 900, 604800);
        when(authenticationService.authenticate("admin", "StrongPass1!"))
                .thenReturn(new AuthenticatedUserPrincipal(1L, "ADMIN"));
        when(authenticationService.verifyAdminTotp(1L, "654321")).thenReturn(true);
        when(tokenService.issueTokenPair(1L, "ADMIN", List.of("ROLE_ADMIN"), 1L))
                .thenReturn(tokenPair);

        var result = loginService.login("admin", "StrongPass1!", null, "654321", null, "127.0.0.1");

        assertThat(result.tokenPair()).isSameAs(tokenPair);
        assertThat(result.response().role()).isEqualTo("ADMIN");
        assertThat(result.response().passwordChangeRequired()).isFalse();
        verify(loginAttemptService).recordSuccess("admin", "127.0.0.1");
    }
}

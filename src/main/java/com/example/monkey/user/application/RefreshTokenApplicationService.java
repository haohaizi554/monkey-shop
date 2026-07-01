package com.example.monkey.user.application;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionTokenPair;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.application.SessionTokenApplicationService.AuthenticatedRefreshToken;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RefreshTokenApplicationService {

    static final String REFRESH_TOKEN_NOT_PROVIDED = "refresh token not provided";
    static final String REFRESH_TOKEN_INVALID = "invalid or expired refresh token";

    private final AuthenticationApplicationService authenticationService;
    private final SessionTokenApplicationService tokenService;
    private final AuditService auditService;

    public RefreshTokenApplicationService(
            AuthenticationApplicationService authenticationService,
            SessionTokenApplicationService tokenService,
            AuditService auditService) {
        this.authenticationService = authenticationService;
        this.tokenService = tokenService;
        this.auditService = auditService;
    }

    public RefreshTokenResult refresh(String rawRefreshToken, String clientIp) {
        if (!StringUtils.hasText(rawRefreshToken)) {
            auditService.record(
                    AuditService.REFRESH_TOKEN_FAILURE,
                    AuditService.OUTCOME_FAILURE,
                    null,
                    null,
                    null,
                    clientIp,
                    "missing_refresh_token");
            throw authFailure(ErrorCode.UNAUTHORIZED, REFRESH_TOKEN_NOT_PROVIDED, false);
        }
        return tokenService
                .parseRefreshToken(rawRefreshToken)
                .map(token -> refreshCurrentPrincipal(token, clientIp))
                .orElseGet(() -> rejectInvalidRefreshToken(rawRefreshToken, clientIp));
    }

    private RefreshTokenResult rejectInvalidRefreshToken(String refreshToken, String clientIp) {
        boolean replayDetected = tokenService.revokeUserTokensForRefreshTokenReuse(refreshToken);
        auditService.record(
                replayDetected ? AuditService.REFRESH_TOKEN_REPLAY : AuditService.REFRESH_TOKEN_FAILURE,
                replayDetected ? AuditService.OUTCOME_DENIED : AuditService.OUTCOME_FAILURE,
                null,
                null,
                null,
                clientIp,
                replayDetected ? "replay_detected" : "invalid_refresh_token");
        throw authFailure(ErrorCode.UNAUTHORIZED, REFRESH_TOKEN_INVALID, true);
    }

    private RefreshTokenResult refreshCurrentPrincipal(AuthenticatedRefreshToken refreshToken, String clientIp) {
        return authenticationService
                .currentPrincipal(refreshToken.userId())
                .map(principal -> {
                    SessionTokenPair pair =
                            tokenService.rotateRefreshToken(refreshToken, principal.role(), principal.authorities());
                    auditService.record(
                            AuditService.REFRESH_TOKEN_SUCCESS,
                            AuditService.OUTCOME_SUCCESS,
                            principal.userId(),
                            principal.role(),
                            null,
                            clientIp,
                            null);
                    return new RefreshTokenResult(pair);
                })
                .orElseGet(() -> rejectRejectedPrincipal(refreshToken, clientIp));
    }

    private RefreshTokenResult rejectRejectedPrincipal(AuthenticatedRefreshToken refreshToken, String clientIp) {
        tokenService.revokeRefreshToken(refreshToken);
        auditService.record(
                AuditService.REFRESH_TOKEN_FAILURE,
                AuditService.OUTCOME_DENIED,
                refreshToken.userId(),
                refreshToken.role(),
                null,
                clientIp,
                "principal_rejected");
        throw authFailure(ErrorCode.UNAUTHORIZED, REFRESH_TOKEN_INVALID, true);
    }

    private static RefreshTokenFailure authFailure(ErrorCode errorCode, String message, boolean clearTokenCookies) {
        String publicMessage = StringUtils.hasText(message) ? message : errorCode.defaultMessage();
        return new RefreshTokenFailure(errorCode, publicMessage, clearTokenCookies);
    }

    public record RefreshTokenResult(SessionTokenPair tokenPair) {}

    public static final class RefreshTokenFailure extends BusinessException {

        private final boolean clearTokenCookies;

        public RefreshTokenFailure(ErrorCode errorCode, String message, boolean clearTokenCookies) {
            super(errorCode, message);
            this.clearTokenCookies = clearTokenCookies;
        }

        public boolean clearTokenCookies() {
            return clearTokenCookies;
        }
    }
}

package com.example.monkey.user.application;

import com.example.monkey.user.domain.AuthPrincipal;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationApplicationService {

    private final UserService userService;

    public AuthenticationApplicationService(UserService userService) {
        this.userService = userService;
    }

    public AuthenticatedUserPrincipal authenticate(String username, String password) {
        return toAuthenticatedUserPrincipal(userService.authenticate(username, password));
    }

    public Optional<AuthenticatedUserPrincipal> currentPrincipal(Long userId) {
        return userService.currentPrincipal(userId).map(AuthenticationApplicationService::toAuthenticatedUserPrincipal);
    }

    public boolean verifyAdminTotp(Long userId, String totpCode) {
        return userService.verifyAdminTotp(userId, totpCode);
    }

    private static AuthenticatedUserPrincipal toAuthenticatedUserPrincipal(AuthPrincipal principal) {
        if (principal == null) {
            return null;
        }
        return new AuthenticatedUserPrincipal(
                principal.userId(), principal.role(), principal.authorities(), principal.passwordChangeRequired());
    }
}

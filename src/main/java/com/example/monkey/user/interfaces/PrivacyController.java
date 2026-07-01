package com.example.monkey.user.interfaces;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.user.application.PrivacyApplicationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/user", "/api/v1/users"})
public class PrivacyController {

    private final PrivacyApplicationService privacyApplicationService;

    public PrivacyController(PrivacyApplicationService privacyApplicationService) {
        this.privacyApplicationService = privacyApplicationService;
    }

    @PostMapping("/forget-me")
    @PreAuthorize("hasAuthority('USER_PROFILE_WRITE')")
    public Result<Void> forgetMe(@AuthenticationPrincipal SessionUser currentUser) {
        privacyApplicationService.forgetUser(currentUser);
        return Result.success();
    }
}

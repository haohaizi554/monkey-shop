package com.example.monkey.controller;

import static com.example.monkey.domain.user.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.domain.user.SessionUser;
import com.example.monkey.service.PiiRetentionService;
import com.example.monkey.shared.api.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/user", "/api/v1/users"})
public class PrivacyController {

    private final PiiRetentionService piiRetentionService;

    public PrivacyController(PiiRetentionService piiRetentionService) {
        this.piiRetentionService = piiRetentionService;
    }

    @PostMapping("/forget-me")
    @PreAuthorize("hasAuthority('USER_PROFILE_WRITE')")
    public Result<Void> forgetMe(@AuthenticationPrincipal SessionUser currentUser) {
        piiRetentionService.forgetUser(requireUserId(currentUser));
        return Result.success();
    }
}

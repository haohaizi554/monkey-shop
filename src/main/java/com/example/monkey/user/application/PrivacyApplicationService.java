package com.example.monkey.user.application;

import static com.example.monkey.shared.application.security.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.shared.application.security.SessionUser;
import org.springframework.stereotype.Service;

@Service
public class PrivacyApplicationService {

    private final PiiRetentionService piiRetentionService;

    public PrivacyApplicationService(PiiRetentionService piiRetentionService) {
        this.piiRetentionService = piiRetentionService;
    }

    public void forgetUser(SessionUser currentUser) {
        piiRetentionService.forgetUser(requireUserId(currentUser));
    }
}

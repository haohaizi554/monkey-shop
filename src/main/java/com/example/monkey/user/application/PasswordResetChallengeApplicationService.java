package com.example.monkey.user.application;

import com.example.monkey.user.domain.PasswordResetChallengeService;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetChallengeApplicationService {

    private final PasswordResetChallengeService passwordResetChallengeService;

    public PasswordResetChallengeApplicationService(PasswordResetChallengeService passwordResetChallengeService) {
        this.passwordResetChallengeService = passwordResetChallengeService;
    }

    public void issueResetChallenge(String username, String phone, String email, boolean targetMatches) {
        passwordResetChallengeService.issueResetChallenge(username, phone, email, targetMatches);
    }

    public boolean consumeResetOtp(String username, String phone, String otp) {
        return passwordResetChallengeService.consumeResetOtp(username, phone, otp);
    }

    public boolean consumeResetChallenge(String username, String phone, String email, String otp, String emailToken) {
        return passwordResetChallengeService.consumeResetChallenge(username, phone, email, otp, emailToken);
    }
}

package com.example.monkey.domain.user;

public interface PasswordResetChallengeService {

    void issueResetOtp(String username, String phone, boolean targetMatches);

    void issueResetChallenge(String username, String phone, String email, boolean targetMatches);

    boolean consumeResetOtp(String username, String phone, String otp);

    boolean consumeResetChallenge(String username, String phone, String email, String otp, String emailToken);
}

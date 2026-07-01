package com.example.monkey.user.domain;

public interface UserMfaVerifier {

    boolean verifyCode(String base32Secret, String code);

    boolean isValidSecret(String base32Secret);
}

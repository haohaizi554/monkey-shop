package com.example.monkey.domain.user;

public interface HumanVerificationService {

    boolean verify(String token, String expectedAction, String remoteIp);
}

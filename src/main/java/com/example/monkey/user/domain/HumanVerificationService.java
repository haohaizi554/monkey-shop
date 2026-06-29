package com.example.monkey.user.domain;

public interface HumanVerificationService {

    boolean verify(String token, String expectedAction, String remoteIp);
}

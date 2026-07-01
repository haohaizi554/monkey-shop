package com.example.monkey.user.domain;

public interface UserPasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}

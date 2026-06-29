package com.example.monkey.domain.user;

public interface UserPasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}

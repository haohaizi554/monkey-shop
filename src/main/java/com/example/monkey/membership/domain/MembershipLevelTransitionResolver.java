package com.example.monkey.membership.domain;

public interface MembershipLevelTransitionResolver {

    void assertAllowed(MembershipLevel currentLevel, MembershipLevel nextLevel);
}

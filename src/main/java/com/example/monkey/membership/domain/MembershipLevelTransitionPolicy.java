package com.example.monkey.membership.domain;

import java.util.List;
import java.util.Optional;

public final class MembershipLevelTransitionPolicy {

    public static final String LEVEL_TRANSITION_NOT_ALLOWED = "Membership level transition is not allowed";

    private static final List<MembershipLevelTransitionRule> TRANSITIONS = List.of(
            new MembershipLevelTransitionRule(
                    MembershipLevel.BASIC, MembershipLevel.SILVER, MembershipLevelTransition.UPGRADE),
            new MembershipLevelTransitionRule(
                    MembershipLevel.SILVER, MembershipLevel.GOLD, MembershipLevelTransition.UPGRADE),
            new MembershipLevelTransitionRule(
                    MembershipLevel.GOLD, MembershipLevel.DIAMOND, MembershipLevelTransition.UPGRADE),
            new MembershipLevelTransitionRule(
                    MembershipLevel.DIAMOND, MembershipLevel.GOLD, MembershipLevelTransition.DOWNGRADE),
            new MembershipLevelTransitionRule(
                    MembershipLevel.GOLD, MembershipLevel.SILVER, MembershipLevelTransition.DOWNGRADE),
            new MembershipLevelTransitionRule(
                    MembershipLevel.SILVER, MembershipLevel.BASIC, MembershipLevelTransition.DOWNGRADE));

    private MembershipLevelTransitionPolicy() {}

    public static Optional<MembershipLevelTransition> transition(MembershipLevel current, MembershipLevel next) {
        if (current == next) {
            return Optional.of(MembershipLevelTransition.KEEP);
        }
        return TRANSITIONS.stream()
                .filter(rule -> rule.source() == current && rule.target() == next)
                .map(MembershipLevelTransitionRule::transition)
                .findFirst();
    }

    public static List<MembershipLevelTransitionRule> transitions() {
        return TRANSITIONS;
    }

    public record MembershipLevelTransitionRule(
            MembershipLevel source, MembershipLevel target, MembershipLevelTransition transition) {}
}

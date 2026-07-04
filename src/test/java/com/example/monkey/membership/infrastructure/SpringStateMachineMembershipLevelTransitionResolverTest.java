package com.example.monkey.membership.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.shared.domain.exception.BusinessException;
import org.junit.jupiter.api.Test;

class SpringStateMachineMembershipLevelTransitionResolverTest {

    private final SpringStateMachineMembershipLevelTransitionResolver resolver =
            new SpringStateMachineMembershipLevelTransitionResolver();

    @Test
    void adjacentTransitionsAreAccepted() {
        assertThatCode(() -> resolver.assertAllowed(MembershipLevel.BASIC, MembershipLevel.SILVER))
                .doesNotThrowAnyException();
        assertThatCode(() -> resolver.assertAllowed(MembershipLevel.GOLD, MembershipLevel.SILVER))
                .doesNotThrowAnyException();
        assertThatCode(() -> resolver.assertAllowed(MembershipLevel.GOLD, MembershipLevel.GOLD))
                .doesNotThrowAnyException();
    }

    @Test
    void skippedTransitionsAreRejected() {
        assertThatThrownBy(() -> resolver.assertAllowed(MembershipLevel.BASIC, MembershipLevel.DIAMOND))
                .isInstanceOf(BusinessException.class);
    }
}

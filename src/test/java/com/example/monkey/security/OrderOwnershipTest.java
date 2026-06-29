package com.example.monkey.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.order.OrderOwnershipChecker;
import com.example.monkey.domain.user.SessionUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class OrderOwnershipTest {

    private final OrderOwnershipChecker orderOwnershipChecker = org.mockito.Mockito.mock(OrderOwnershipChecker.class);
    private final OrderOwnership orderOwnership = new OrderOwnership(orderOwnershipChecker);

    @Test
    void ownerCanAccessOwnOrder() {
        UsernamePasswordAuthenticationToken authentication =
                authenticated(new SessionUser(7L, SessionIdentity.ROLE_USER));
        when(orderOwnershipChecker.isVisibleOwner(42L, 7L)).thenReturn(true);

        assertThat(orderOwnership.isOwner(42L, authentication)).isTrue();

        verify(orderOwnershipChecker).isVisibleOwner(42L, 7L);
    }

    @Test
    void differentUserCannotAccessOrder() {
        UsernamePasswordAuthenticationToken authentication =
                authenticated(new SessionUser(8L, SessionIdentity.ROLE_USER));
        when(orderOwnershipChecker.isVisibleOwner(42L, 8L)).thenReturn(false);

        assertThat(orderOwnership.isOwner(42L, authentication)).isFalse();

        verify(orderOwnershipChecker).isVisibleOwner(42L, 8L);
    }

    @Test
    void invalidAuthenticationNeverQueriesRepository() {
        assertThat(orderOwnership.isOwner(null, authenticated(new SessionUser(7L, SessionIdentity.ROLE_USER))))
                .isFalse();
        assertThat(orderOwnership.isOwner(42L, null)).isFalse();
        assertThat(orderOwnership.isOwner(42L, new UsernamePasswordAuthenticationToken("legacy", null)))
                .isFalse();

        verifyNoInteractions(orderOwnershipChecker);
    }

    private static UsernamePasswordAuthenticationToken authenticated(SessionUser sessionUser) {
        return new UsernamePasswordAuthenticationToken(
                sessionUser, null, List.of(new SimpleGrantedAuthority("ROLE_" + sessionUser.role())));
    }
}

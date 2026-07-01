package com.example.monkey.order.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.order.application.OrderOwnershipService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.user.domain.UserRoles;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class OrderOwnershipTest {

    private final OrderOwnershipService orderOwnershipService = org.mockito.Mockito.mock(OrderOwnershipService.class);
    private final OrderOwnership orderOwnership = new OrderOwnership(orderOwnershipService);

    @Test
    void ownerCanAccessOwnOrder() {
        UsernamePasswordAuthenticationToken authentication = authenticated(new SessionUser(7L, UserRoles.USER));
        when(orderOwnershipService.isVisibleOwner(42L, 7L)).thenReturn(true);

        assertThat(orderOwnership.isOwner(42L, authentication)).isTrue();

        verify(orderOwnershipService).isVisibleOwner(42L, 7L);
    }

    @Test
    void differentUserCannotAccessOrder() {
        UsernamePasswordAuthenticationToken authentication = authenticated(new SessionUser(8L, UserRoles.USER));
        when(orderOwnershipService.isVisibleOwner(42L, 8L)).thenReturn(false);

        assertThat(orderOwnership.isOwner(42L, authentication)).isFalse();

        verify(orderOwnershipService).isVisibleOwner(42L, 8L);
    }

    @Test
    void invalidAuthenticationNeverQueriesRepository() {
        assertThat(orderOwnership.isOwner(null, authenticated(new SessionUser(7L, UserRoles.USER))))
                .isFalse();
        assertThat(orderOwnership.isOwner(42L, null)).isFalse();
        assertThat(orderOwnership.isOwner(42L, new UsernamePasswordAuthenticationToken("legacy", null)))
                .isFalse();

        verifyNoInteractions(orderOwnershipService);
    }

    private static UsernamePasswordAuthenticationToken authenticated(SessionUser sessionUser) {
        return new UsernamePasswordAuthenticationToken(
                sessionUser, null, List.of(new SimpleGrantedAuthority("ROLE_" + sessionUser.role())));
    }
}

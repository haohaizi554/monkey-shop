package com.example.monkey.cart.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.cart.application.CartApplicationService;
import com.example.monkey.cart.application.dto.CartCheckoutResponseDto;
import com.example.monkey.cart.application.dto.CartDirectCheckoutRequestDto;
import com.example.monkey.shared.application.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class CartControllerTest {

    @Test
    void directCheckoutDelegatesRiskSignalsToTheApplicationBoundary() {
        CartApplicationService cartApplicationService = mock(CartApplicationService.class);
        CartController controller = new CartController(cartApplicationService);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        SessionUser currentUser = new SessionUser(7L, "USER");
        CartDirectCheckoutRequestDto request = new CartDirectCheckoutRequestDto(1002L, 9L, 3, 5L, "CN-BJ", List.of());
        CartCheckoutResponseDto response = mock(CartCheckoutResponseDto.class);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(cartApplicationService.directCheckout(currentUser, request, "direct-key", "device-1", "127.0.0.1"))
                .thenReturn(response);

        var result = controller.directCheckout("direct-key", "device-1", request, currentUser, httpRequest);

        assertThat(result.data()).isSameAs(response);
        verify(cartApplicationService).directCheckout(currentUser, request, "direct-key", "device-1", "127.0.0.1");
    }
}

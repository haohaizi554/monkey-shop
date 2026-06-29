package com.example.monkey.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.domain.product.ProductCatalog.ProductPageRequest;
import com.example.monkey.domain.product.ProductCatalog.SortOrder;
import com.example.monkey.domain.product.ProductCatalog.SortOrder.Direction;
import com.example.monkey.dto.MonkeyRequestDto;
import com.example.monkey.dto.MonkeyResponseDto;
import com.example.monkey.dto.PageResponseDto;
import com.example.monkey.service.MonkeyService;
import com.example.monkey.shared.api.Result;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class MonkeyControllerTest {

    @Mock
    private MonkeyService monkeyService;

    private MonkeyController controller;

    @BeforeEach
    void setUp() {
        controller = new MonkeyController(monkeyService);
    }

    @Test
    void getAllMonkeysDelegatesToService() {
        MonkeyResponseDto monkey = response();
        when(monkeyService.findAllMonkeys()).thenReturn(List.of(monkey));

        Result<List<MonkeyResponseDto>> result = controller.getAllMonkeys();

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).containsExactly(monkey);
        verify(monkeyService).findAllMonkeys();
    }

    @Test
    void getMonkeysPageDelegatesToService() {
        MonkeyResponseDto monkey = response();
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("price"), Sort.Order.asc("name")));
        PageResponseDto<MonkeyResponseDto> page = new PageResponseDto<>(List.of(monkey), 0, 20, 1, 1, true, true);
        when(monkeyService.findMonkeys(any(ProductPageRequest.class))).thenReturn(page);

        Result<PageResponseDto<MonkeyResponseDto>> result = controller.getMonkeys(pageable);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(page);
        ProductPageRequest pageRequest = capturePageRequest();
        assertThat(pageRequest.page()).isZero();
        assertThat(pageRequest.size()).isEqualTo(20);
        assertThat(pageRequest.sortOrders())
                .containsExactly(new SortOrder("price", Direction.DESC), new SortOrder("name", Direction.ASC));
    }

    @Test
    void addMonkeyDelegatesToService() {
        MonkeyRequestDto request = request();
        MonkeyResponseDto monkey = response();
        when(monkeyService.addMonkey(request)).thenReturn(monkey);

        Result<MonkeyResponseDto> result = controller.addMonkey(request);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(monkey);
        verify(monkeyService).addMonkey(request);
    }

    @Test
    void updateMonkeyDelegatesToService() {
        MonkeyRequestDto request = request();
        MonkeyResponseDto monkey = response();
        when(monkeyService.updateMonkey(request)).thenReturn(monkey);

        Result<MonkeyResponseDto> result = controller.updateMonkey(request);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(monkey);
        verify(monkeyService).updateMonkey(request);
    }

    @Test
    void deleteMonkeyDelegatesToService() {
        Result<Void> result = controller.deleteMonkey(7L);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(monkeyService).deleteMonkey(7L);
    }

    private static MonkeyRequestDto request() {
        return new MonkeyRequestDto(7L, "Momo", "Golden", BigDecimal.valueOf(199.99), "bright", "/images/momo.png", 5);
    }

    private static MonkeyResponseDto response() {
        return new MonkeyResponseDto(7L, "Momo", "Golden", BigDecimal.valueOf(199.99), "bright", "/images/momo.png", 5);
    }

    private ProductPageRequest capturePageRequest() {
        ArgumentCaptor<ProductPageRequest> captor = ArgumentCaptor.forClass(ProductPageRequest.class);
        verify(monkeyService).findMonkeys(captor.capture());
        return captor.getValue();
    }
}

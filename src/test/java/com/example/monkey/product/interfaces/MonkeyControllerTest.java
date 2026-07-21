package com.example.monkey.product.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.product.application.MonkeyService;
import com.example.monkey.product.application.dto.MonkeyRequestDto;
import com.example.monkey.product.application.dto.MonkeyResponseDto;
import com.example.monkey.product.application.dto.ProductPageQuery;
import com.example.monkey.product.application.dto.ProductPageQuery.SortOrder;
import com.example.monkey.product.application.dto.ProductPageQuery.SortOrder.Direction;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.interfaces.dto.Result;
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
    void getMonkeysDelegatesToServiceWithDefaultPage() {
        MonkeyResponseDto monkey = response();
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.asc("id")));
        PageResponseDto<MonkeyResponseDto> page = new PageResponseDto<>(List.of(monkey), 0, 20, 1, 1, true, true);
        when(monkeyService.findMonkeys(any(ProductPageQuery.class))).thenReturn(page);

        Result<PageResponseDto<MonkeyResponseDto>> result = controller.getMonkeys(null, null, null, null, pageable);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(page);
        ProductPageQuery pageQuery = capturePageQuery();
        assertThat(pageQuery.page()).isZero();
        assertThat(pageQuery.size()).isEqualTo(20);
    }

    @Test
    void getMonkeysPageDelegatesToService() {
        MonkeyResponseDto monkey = response();
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Order.desc("price"), Sort.Order.asc("name")));
        PageResponseDto<MonkeyResponseDto> page = new PageResponseDto<>(List.of(monkey), 0, 20, 1, 1, true, true);
        when(monkeyService.findMonkeys(any(ProductPageQuery.class))).thenReturn(page);

        Result<PageResponseDto<MonkeyResponseDto>> result =
                controller.getMonkeys(" golden ", BigDecimal.valueOf(100), BigDecimal.valueOf(300), true, pageable);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isSameAs(page);
        ProductPageQuery pageQuery = capturePageQuery();
        assertThat(pageQuery.page()).isZero();
        assertThat(pageQuery.size()).isEqualTo(20);
        assertThat(pageQuery.sortOrders())
                .containsExactly(new SortOrder("price", Direction.DESC), new SortOrder("name", Direction.ASC));
        assertThat(pageQuery.keyword()).isEqualTo("golden");
        assertThat(pageQuery.minPrice()).isEqualByComparingTo("100");
        assertThat(pageQuery.maxPrice()).isEqualByComparingTo("300");
        assertThat(pageQuery.inStock()).isTrue();
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

    private ProductPageQuery capturePageQuery() {
        ArgumentCaptor<ProductPageQuery> captor = ArgumentCaptor.forClass(ProductPageQuery.class);
        verify(monkeyService).findMonkeys(captor.capture());
        return captor.getValue();
    }
}

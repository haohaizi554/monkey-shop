package com.example.monkey.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.product.application.dto.MonkeyRequestDto;
import com.example.monkey.product.application.dto.MonkeyResponseDto;
import com.example.monkey.product.application.dto.ProductPageQuery;
import com.example.monkey.product.domain.ProductCatalog;
import com.example.monkey.product.domain.ProductCatalog.ProductPage;
import com.example.monkey.product.domain.ProductCatalog.ProductPageRequest;
import com.example.monkey.product.domain.ProductCatalog.ProductRecord;
import com.example.monkey.product.domain.ProductCatalog.SortOrder;
import com.example.monkey.product.domain.ProductCatalog.SortOrder.Direction;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.storage.ImageCleanupService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonkeyServiceTest {

    @Mock
    private ProductCatalog productCatalog;

    @Mock
    private ImageCleanupService imageCleanupService;

    @Mock
    private ImageReferenceService imageReferenceService;

    private MonkeyService monkeyService;

    @BeforeEach
    void setUp() {
        monkeyService = new MonkeyService(productCatalog, imageCleanupService, imageReferenceService);
    }

    @Test
    void findAllMonkeysUsesBoundedLegacyPageRequest() {
        ProductRecord product = product();
        when(productCatalog.findPage(any(ProductPageRequest.class)))
                .thenReturn(new ProductPage(List.of(product), 0, 100, 1, 1, true, true));

        List<MonkeyResponseDto> result = monkeyService.findAllMonkeys();

        assertThat(result).containsExactly(response());
        ProductPageRequest pageRequest = capturePageRequest();
        assertThat(pageRequest.page()).isZero();
        assertThat(pageRequest.size()).isEqualTo(100);
        assertThat(pageRequest.sortOrders()).containsExactly(new SortOrder("id", Direction.ASC));
    }

    @Test
    void findMonkeysMapsPagedCatalogResultsToStableEnvelope() {
        ProductPageQuery pageQuery = new ProductPageQuery(
                1,
                10,
                List.of(
                        new ProductPageQuery.SortOrder("price", ProductPageQuery.SortOrder.Direction.DESC),
                        new ProductPageQuery.SortOrder("name", ProductPageQuery.SortOrder.Direction.ASC)));
        ProductPageRequest expectedPageRequest = new ProductPageRequest(
                1, 10, List.of(new SortOrder("price", Direction.DESC), new SortOrder("name", Direction.ASC)));
        ProductPage page = new ProductPage(List.of(product()), 1, 10, 23, 3, false, false);
        when(productCatalog.findPage(expectedPageRequest)).thenReturn(page);

        PageResponseDto<MonkeyResponseDto> result = monkeyService.findMonkeys(pageQuery);

        assertThat(result.content()).containsExactly(response());
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(23);
        assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.first()).isFalse();
        assertThat(result.last()).isFalse();

        verify(productCatalog).findPage(expectedPageRequest);
    }

    @Test
    void addMonkeyAppliesDefaultImageWhenMissing() {
        MonkeyRequestDto request = new MonkeyRequestDto(null, "Momo", "Golden", BigDecimal.TEN, "bright", "", 5);

        MonkeyResponseDto result = monkeyService.addMonkey(request);

        assertThat(result.imageUrl()).isEqualTo("/images/default_product.png");
        ProductRecord savedProduct = captureSavedProduct();
        assertThat(savedProduct.imageUrl()).isEqualTo("/images/default_product.png");
        assertThat(savedProduct.name()).isEqualTo("Momo");
        verify(imageReferenceService).retain("/images/default_product.png");
    }

    @Test
    void addMonkeyPreservesProvidedImage() {
        MonkeyRequestDto request =
                new MonkeyRequestDto(null, "Momo", "Golden", BigDecimal.TEN, "bright", "/images/product/custom.png", 5);

        MonkeyResponseDto result = monkeyService.addMonkey(request);

        assertThat(result.imageUrl()).isEqualTo("/images/product/custom.png");
        assertThat(captureSavedProduct().imageUrl()).isEqualTo("/images/product/custom.png");
        verify(imageReferenceService).retain("/images/product/custom.png");
    }

    @Test
    void updateMonkeyRejectsMissingProduct() {
        MonkeyRequestDto request =
                new MonkeyRequestDto(7L, "Momo", "Golden", BigDecimal.TEN, "bright", "/images/product/new.png", 5);
        when(productCatalog.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> monkeyService.updateMonkey(request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(productCatalog, never()).save(any(ProductRecord.class));
        verify(imageCleanupService, never()).tryDelete(any());
    }

    @Test
    void updateMonkeyDeletesOldImageWhenChanged() {
        ProductRecord oldProduct = productWithImage("/images/product/old.png");
        MonkeyRequestDto request =
                new MonkeyRequestDto(7L, "Momo", "Golden", BigDecimal.TEN, "bright", "/images/product/new.png", 5);
        when(productCatalog.findById(7L)).thenReturn(Optional.of(oldProduct));

        MonkeyResponseDto result = monkeyService.updateMonkey(request);

        assertThat(result.imageUrl()).isEqualTo("/images/product/new.png");
        assertThat(captureSavedProduct().imageUrl()).isEqualTo("/images/product/new.png");
        verify(imageReferenceService).retain("/images/product/new.png");
        verify(imageReferenceService).release("/images/product/old.png");
        verify(imageCleanupService).tryDelete("/images/product/old.png");
    }

    @Test
    void updateMonkeyKeepsImageWhenUnchanged() {
        ProductRecord oldProduct = productWithImage("/images/product/same.png");
        MonkeyRequestDto request =
                new MonkeyRequestDto(7L, "Momo", "Golden", BigDecimal.TEN, "bright", "/images/product/same.png", 5);
        when(productCatalog.findById(7L)).thenReturn(Optional.of(oldProduct));

        MonkeyResponseDto result = monkeyService.updateMonkey(request);

        assertThat(result.imageUrl()).isEqualTo("/images/product/same.png");
        assertThat(captureSavedProduct().imageUrl()).isEqualTo("/images/product/same.png");
        verify(imageCleanupService, never()).tryDelete(any());
    }

    @Test
    void deleteMonkeyRejectsMissingProduct() {
        when(productCatalog.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> monkeyService.deleteMonkey(7L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(productCatalog, never()).deleteById(7L);
        verify(imageCleanupService, never()).tryDelete(any());
    }

    @Test
    void deleteMonkeyDeletesImageAfterCatalogDelete() {
        ProductRecord product = productWithImage("/images/product/delete.png");
        when(productCatalog.findById(7L)).thenReturn(Optional.of(product));

        monkeyService.deleteMonkey(7L);

        verify(productCatalog).deleteById(7L);
        verify(imageReferenceService).release("/images/product/delete.png");
        verify(imageCleanupService).tryDelete("/images/product/delete.png");
    }

    private ProductRecord captureSavedProduct() {
        ArgumentCaptor<ProductRecord> captor = ArgumentCaptor.forClass(ProductRecord.class);
        verify(productCatalog).save(captor.capture());
        return captor.getValue();
    }

    private ProductPageRequest capturePageRequest() {
        ArgumentCaptor<ProductPageRequest> captor = ArgumentCaptor.forClass(ProductPageRequest.class);
        verify(productCatalog).findPage(captor.capture());
        return captor.getValue();
    }

    private static ProductRecord product() {
        return productWithImage("/images/momo.png");
    }

    private static ProductRecord productWithImage(String imageUrl) {
        return new ProductRecord(7L, "Momo", "Golden", BigDecimal.valueOf(199.99), "bright", imageUrl, 5);
    }

    private static MonkeyResponseDto response() {
        return new MonkeyResponseDto(7L, "Momo", "Golden", BigDecimal.valueOf(199.99), "bright", "/images/momo.png", 5);
    }
}

package com.example.monkey.product.application;

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
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonkeyService {

    private static final String DEFAULT_PRODUCT_IMAGE = "/images/default_product.png";
    private static final int LEGACY_LIST_PAGE_SIZE = 100;
    private static final ProductPageRequest LEGACY_LIST_REQUEST =
            new ProductPageRequest(0, LEGACY_LIST_PAGE_SIZE, List.of(new SortOrder("id", Direction.ASC)));

    private final ProductCatalog productCatalog;
    private final ImageCleanupService imageCleanupService;
    private final ImageReferenceService imageReferenceService;

    public MonkeyService(
            ProductCatalog productCatalog,
            ImageCleanupService imageCleanupService,
            ImageReferenceService imageReferenceService) {
        this.productCatalog = productCatalog;
        this.imageCleanupService = imageCleanupService;
        this.imageReferenceService = imageReferenceService;
    }

    @Transactional(readOnly = true)
    public List<MonkeyResponseDto> findAllMonkeys() {
        return productCatalog.findPage(LEGACY_LIST_REQUEST).content().stream()
                .map(MonkeyDtoAssembler::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponseDto<MonkeyResponseDto> findMonkeys(ProductPageQuery pageQuery) {
        ProductPage page = productCatalog.findPage(toProductPageRequest(pageQuery));
        return PageResponseDto.from(
                page.content().stream().map(MonkeyDtoAssembler::toResponse).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.first(),
                page.last());
    }

    @Transactional
    public MonkeyResponseDto addMonkey(MonkeyRequestDto request) {
        ProductRecord product = MonkeyDtoAssembler.toProductRecord(request, withDefaultImage(request.imageUrl()));
        ProductRecord savedProduct = productCatalog.save(product);
        imageReferenceService.retain(product.imageUrl());
        return MonkeyDtoAssembler.toResponse(savedProduct != null ? savedProduct : product);
    }

    @Transactional
    public MonkeyResponseDto updateMonkey(MonkeyRequestDto request) {
        ProductRecord product = MonkeyDtoAssembler.toProductRecord(request);
        ProductRecord oldProduct = productCatalog
                .findById(product.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Product does not exist"));
        String oldImage = oldProduct.imageUrl();
        ProductRecord savedProduct = productCatalog.save(product);
        if (oldImage != null && !oldImage.equals(product.imageUrl())) {
            imageReferenceService.retain(product.imageUrl());
            imageReferenceService.release(oldImage);
            imageCleanupService.tryDelete(oldImage);
        }
        return MonkeyDtoAssembler.toResponse(savedProduct != null ? savedProduct : product);
    }

    @Transactional
    public void deleteMonkey(Long id) {
        ProductRecord product = productCatalog
                .findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Product does not exist"));
        String imageToDelete = product.imageUrl();
        productCatalog.deleteById(id);
        imageReferenceService.release(imageToDelete);
        imageCleanupService.tryDelete(imageToDelete);
    }

    private static ProductPageRequest toProductPageRequest(ProductPageQuery pageQuery) {
        List<SortOrder> sortOrders = pageQuery.sortOrders().stream()
                .map(MonkeyService::toDomainSortOrder)
                .toList();
        return new ProductPageRequest(
                pageQuery.page(),
                pageQuery.size(),
                sortOrders,
                pageQuery.keyword(),
                pageQuery.minPrice(),
                pageQuery.maxPrice(),
                pageQuery.inStock());
    }

    private static SortOrder toDomainSortOrder(ProductPageQuery.SortOrder sortOrder) {
        Direction direction =
                sortOrder.direction() == ProductPageQuery.SortOrder.Direction.DESC ? Direction.DESC : Direction.ASC;
        return new SortOrder(sortOrder.property(), direction);
    }

    private static String withDefaultImage(String imageUrl) {
        return imageUrl == null || imageUrl.isEmpty() ? DEFAULT_PRODUCT_IMAGE : imageUrl;
    }
}

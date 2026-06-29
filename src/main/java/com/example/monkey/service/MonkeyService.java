package com.example.monkey.service;

import com.example.monkey.assembler.MonkeyDtoAssembler;
import com.example.monkey.domain.product.ProductCatalog;
import com.example.monkey.domain.product.ProductCatalog.ProductPage;
import com.example.monkey.domain.product.ProductCatalog.ProductPageRequest;
import com.example.monkey.domain.product.ProductCatalog.ProductRecord;
import com.example.monkey.domain.storage.ImageReferenceService;
import com.example.monkey.dto.MonkeyRequestDto;
import com.example.monkey.dto.MonkeyResponseDto;
import com.example.monkey.dto.PageResponseDto;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonkeyService {

    private static final String DEFAULT_PRODUCT_IMAGE = "/images/default_product.png";

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
        return productCatalog.findAll().stream()
                .map(MonkeyDtoAssembler::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponseDto<MonkeyResponseDto> findMonkeys(ProductPageRequest pageRequest) {
        ProductPage page = productCatalog.findPage(pageRequest);
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

    private static String withDefaultImage(String imageUrl) {
        return imageUrl == null || imageUrl.isEmpty() ? DEFAULT_PRODUCT_IMAGE : imageUrl;
    }
}

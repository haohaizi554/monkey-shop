package com.example.monkey.product.application;

import com.example.monkey.product.application.dto.CatalogCreateSpuRequestDto;
import com.example.monkey.product.application.dto.CatalogPriceQuoteDto;
import com.example.monkey.product.application.dto.CatalogSpecificationDimensionDto;
import com.example.monkey.product.application.dto.CatalogSpuResponseDto;
import com.example.monkey.product.application.dto.CategoryNodeResponseDto;
import com.example.monkey.product.domain.CatalogSku;
import com.example.monkey.product.domain.CatalogSpu;
import com.example.monkey.product.domain.CatalogStore;
import com.example.monkey.product.domain.CategoryNode;
import com.example.monkey.product.domain.CategoryTreeCache;
import com.example.monkey.product.domain.PriceContext;
import com.example.monkey.product.domain.ProductPriceBook;
import com.example.monkey.product.domain.ProductPriceQuote;
import com.example.monkey.product.domain.ProductPriceStrategy;
import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.product.domain.SkuCartesianProductGenerator;
import com.example.monkey.product.domain.SkuSpecification;
import com.example.monkey.product.domain.SpecificationDimension;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductCatalogApplicationService {

    private static final int MAX_SKU_COUNT = 200;

    private final CatalogStore catalogStore;
    private final CategoryTreeCache categoryTreeCache;
    private final IdGenerator idGenerator;
    private final ProductPriceStrategy priceStrategy;
    private final AuditService auditService;

    public ProductCatalogApplicationService(
            CatalogStore catalogStore,
            CategoryTreeCache categoryTreeCache,
            IdGenerator idGenerator,
            ProductPriceStrategy priceStrategy,
            AuditService auditService) {
        this.catalogStore = catalogStore;
        this.categoryTreeCache = categoryTreeCache;
        this.idGenerator = idGenerator;
        this.priceStrategy = priceStrategy;
        this.auditService = auditService;
    }

    @WithSpan("catalog.create-spu")
    @Transactional
    public CatalogSpuResponseDto createSpu(CatalogCreateSpuRequestDto request) {
        if (!catalogStore.isLeafCategory(request.categoryId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Product must bind to a level-3 category");
        }
        long spuId = idGenerator.nextId();
        ProductPriceBook priceBook = priceBook(
                request.originalPrice(), request.memberPrice(), request.strikePrice(), request.regionPrices());
        List<SkuSpecification> specifications = SkuCartesianProductGenerator.generate(toDimensions(request));
        if (specifications.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "At least one SKU specification is required");
        }
        if (specifications.size() > MAX_SKU_COUNT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "SKU count exceeds catalog safety limit");
        }

        List<CatalogSku> skus = specifications.stream()
                .map(specification -> new CatalogSku(
                        idGenerator.nextId(),
                        spuId,
                        "SPU-" + spuId + "-" + specification.codeSuffix(),
                        specification,
                        priceBook,
                        true))
                .toList();
        CatalogSpu saved = catalogStore.save(new CatalogSpu(
                spuId,
                request.categoryId(),
                request.name(),
                request.title(),
                ProductStatus.DRAFT,
                priceBook,
                request.attributes(),
                request.detailJsonLd(),
                request.supplierPrivateRemark(),
                request.imageUrl(),
                skus));
        categoryTreeCache.evict();
        auditService.record(
                AuditService.PRODUCT_SPU_CREATED,
                AuditService.OUTCOME_SUCCESS,
                null,
                "SYSTEM",
                "product-spu:" + spuId,
                null,
                "categoryId=" + request.categoryId() + ",skuCount=" + skus.size());
        return CatalogDtoAssembler.toResponse(saved);
    }

    @WithSpan("catalog.get-spu")
    @Transactional(readOnly = true)
    public CatalogSpuResponseDto getSpu(Long spuId) {
        return catalogStore
                .findSpuById(spuId)
                .map(CatalogDtoAssembler::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SPU does not exist"));
    }

    @WithSpan("catalog.transition-status")
    @Transactional
    public CatalogSpuResponseDto transitionStatus(Long spuId, ProductStatus targetStatus) {
        CatalogSpu spu = catalogStore
                .findSpuById(spuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SPU does not exist"));
        CatalogSpu saved = catalogStore.save(spu.transitionTo(targetStatus));
        auditService.record(
                AuditService.PRODUCT_STATUS_CHANGED,
                AuditService.OUTCOME_SUCCESS,
                null,
                "SYSTEM",
                "product-spu:" + spuId,
                null,
                "from=" + spu.status() + ",to=" + targetStatus);
        return CatalogDtoAssembler.toResponse(saved);
    }

    @WithSpan("catalog.quote-price")
    @Transactional(readOnly = true)
    public CatalogPriceQuoteDto quotePrice(Long spuId, String userIdentity, String region) {
        CatalogSpu spu = catalogStore
                .findSpuById(spuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SPU does not exist"));
        ProductPriceQuote quote = priceStrategy.quote(spu.priceBook(), new PriceContext(userIdentity, region));
        return new CatalogPriceQuoteDto(spu.id(), quote.salePrice(), quote.strikePrice(), quote.strategy());
    }

    @WithSpan("catalog.category-tree")
    @Transactional(readOnly = true)
    public List<CategoryNodeResponseDto> categoryTree() {
        List<CategoryNode> tree = categoryTreeCache.get().orElseGet(() -> {
            List<CategoryNode> loadedTree = catalogStore.findCategoryTree();
            categoryTreeCache.put(loadedTree);
            return loadedTree;
        });
        return tree.stream().map(CatalogDtoAssembler::toResponse).toList();
    }

    private static List<SpecificationDimension> toDimensions(CatalogCreateSpuRequestDto request) {
        return request.specifications().stream()
                .map(ProductCatalogApplicationService::toDimension)
                .toList();
    }

    private static SpecificationDimension toDimension(CatalogSpecificationDimensionDto dto) {
        return new SpecificationDimension(dto.name(), dto.values());
    }

    private static ProductPriceBook priceBook(
            BigDecimal originalPrice,
            BigDecimal memberPrice,
            BigDecimal strikePrice,
            Map<String, BigDecimal> regionPrices) {
        return new ProductPriceBook(originalPrice, memberPrice, strikePrice, regionPrices);
    }
}

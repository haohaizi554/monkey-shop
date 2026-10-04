package com.example.monkey.product.application;

import com.example.monkey.product.application.dto.CatalogCreateSpuRequestDto;
import com.example.monkey.product.application.dto.CatalogManagementPageQueryDto;
import com.example.monkey.product.application.dto.CatalogPriceQuoteDto;
import com.example.monkey.product.application.dto.CatalogSpecificationDimensionDto;
import com.example.monkey.product.application.dto.CatalogSpuResponseDto;
import com.example.monkey.product.application.dto.CatalogUpdateSpuRequestDto;
import com.example.monkey.product.application.dto.CategoryNodeResponseDto;
import com.example.monkey.product.domain.CatalogSku;
import com.example.monkey.product.domain.CatalogSpu;
import com.example.monkey.product.domain.CatalogStore;
import com.example.monkey.product.domain.CategoryNode;
import com.example.monkey.product.domain.CategoryTreeCache;
import com.example.monkey.product.domain.PriceContext;
import com.example.monkey.product.domain.ProductPriceBook;
import com.example.monkey.product.domain.ProductPriceContextResolver;
import com.example.monkey.product.domain.ProductPriceQuote;
import com.example.monkey.product.domain.ProductPriceStrategy;
import com.example.monkey.product.domain.ProductStatus;
import com.example.monkey.product.domain.SkuCartesianProductGenerator;
import com.example.monkey.product.domain.SkuSpecification;
import com.example.monkey.product.domain.SpecificationDimension;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.storage.ImageReferenceTransactions;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductCatalogApplicationService {

    private static final int MAX_SKU_COUNT = 200;
    private static final Pattern POSITIVE_LONG = Pattern.compile("[1-9][0-9]*");

    private final CatalogStore catalogStore;
    private final CategoryTreeCache categoryTreeCache;
    private final IdGenerator idGenerator;
    private final ProductPriceStrategy priceStrategy;
    private final ProductPriceContextResolver priceContextResolver;
    private final AuditService auditService;
    private final ImageReferenceService imageReferenceService;

    @Autowired
    public ProductCatalogApplicationService(
            CatalogStore catalogStore,
            CategoryTreeCache categoryTreeCache,
            IdGenerator idGenerator,
            ProductPriceStrategy priceStrategy,
            ProductPriceContextResolver priceContextResolver,
            AuditService auditService,
            ImageReferenceService imageReferenceService) {
        this.catalogStore = catalogStore;
        this.categoryTreeCache = categoryTreeCache;
        this.idGenerator = idGenerator;
        this.priceStrategy = priceStrategy;
        this.priceContextResolver = priceContextResolver;
        this.auditService = auditService;
        this.imageReferenceService = imageReferenceService;
    }

    /** Compatibility overload for read-only direct callers; catalog writes fail closed without tracking. */
    public ProductCatalogApplicationService(
            CatalogStore catalogStore,
            CategoryTreeCache categoryTreeCache,
            IdGenerator idGenerator,
            ProductPriceStrategy priceStrategy,
            ProductPriceContextResolver priceContextResolver,
            AuditService auditService) {
        this(
                catalogStore,
                categoryTreeCache,
                idGenerator,
                priceStrategy,
                priceContextResolver,
                auditService,
                UnconfiguredImageReferenceService.INSTANCE);
    }

    @WithSpan("catalog.create-spu")
    @Transactional
    public CatalogSpuResponseDto createSpu(CatalogCreateSpuRequestDto request) {
        if (!catalogStore.isLeafCategory(request.categoryId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Product must bind to a level-3 category");
        }
        Long shopId = resolveShopId(request);
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
        CatalogSpu spu = new CatalogSpu(
                spuId,
                request.categoryId(),
                shopId,
                request.name(),
                request.title(),
                ProductStatus.DRAFT,
                priceBook,
                request.attributes(),
                request.detailJsonLd(),
                request.supplierPrivateRemark(),
                request.imageUrl(),
                skus);
        ImageReferenceTransactions.retainBeforeWrite(imageReferenceService, spu.imageUrl());
        CatalogSpu saved = catalogStore.save(spu);
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

    @WithSpan("catalog.management-page")
    @Transactional(readOnly = true)
    public PageResponseDto<CatalogSpuResponseDto> findManagementPage(CatalogManagementPageQueryDto request) {
        CatalogManagementPageQueryDto safeRequest = request == null
                ? new CatalogManagementPageQueryDto(0, 20, null, null)
                : request;
        CatalogStore.CatalogPage page = catalogStore.findManagementPage(safeRequest.toRequest());
        return PageResponseDto.from(
                page.content().stream().map(CatalogDtoAssembler::toResponse).toList(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                page.first(),
                page.last());
    }

    @WithSpan("catalog.update-spu")
    @Transactional
    public CatalogSpuResponseDto updateSpu(Long spuId, CatalogUpdateSpuRequestDto request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Catalog update request is required");
        }
        CatalogSpu existing = requireManagementSpu(spuId);
        if (existing.status() == ProductStatus.RECYCLED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Recycled products cannot be edited");
        }
        if (!catalogStore.isLeafCategory(request.categoryId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Product must bind to a level-3 category");
        }
        Long shopId = resolveShopId(request.shopId(), request.attributes());
        ProductPriceBook priceBook = priceBook(
                request.originalPrice(), request.memberPrice(), request.strikePrice(), request.regionPrices());
        List<SkuSpecification> specifications = request.specifications().stream()
                .map(ProductCatalogApplicationService::toDimension)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(), SkuCartesianProductGenerator::generate));
        validateSkuCount(specifications);

        Map<Map<String, String>, CatalogSku> existingBySpecification = new HashMap<>();
        for (CatalogSku sku : existing.skus()) {
            existingBySpecification.put(sku.specification().values(), sku);
        }
        List<CatalogSku> skus = specifications.stream()
                .map(specification -> {
                    CatalogSku previous = existingBySpecification.get(specification.values());
                    return new CatalogSku(
                            previous == null ? idGenerator.nextId() : previous.id(),
                            spuId,
                            previous == null
                                    ? "SPU-" + spuId + "-" + specification.codeSuffix()
                                    : previous.skuCode(),
                            specification,
                            priceBook,
                            true);
                })
                .toList();
        CatalogSpu updated = new CatalogSpu(
                spuId,
                request.categoryId(),
                shopId,
                request.name(),
                request.title(),
                existing.status(),
                priceBook,
                request.attributes(),
                request.detailJsonLd(),
                request.supplierPrivateRemark(),
                request.imageUrl(),
                skus);
        if (!Objects.equals(existing.imageUrl(), updated.imageUrl())) {
            ImageReferenceTransactions.retainBeforeWrite(imageReferenceService, updated.imageUrl());
        }
        CatalogSpu saved = catalogStore.save(updated);
        categoryTreeCache.evict();
        return CatalogDtoAssembler.toResponse(saved);
    }

    @WithSpan("catalog.retire-spu")
    @Transactional
    public CatalogSpuResponseDto retireSpu(Long spuId) {
        CatalogSpu existing = requireManagementSpu(spuId);
        if (existing.status() == ProductStatus.RECYCLED) {
            return CatalogDtoAssembler.toResponse(existing);
        }
        CatalogSpu retired = existing;
        if (retired.status() == ProductStatus.LISTED) {
            retired = retired.transitionTo(ProductStatus.UNLISTED);
        } else if (retired.status() == ProductStatus.PENDING_REVIEW) {
            retired = retired.transitionTo(ProductStatus.DRAFT);
        }
        retired = retired.transitionTo(ProductStatus.RECYCLED);
        CatalogSpu saved = catalogStore.save(retired);
        auditService.record(
                AuditService.PRODUCT_STATUS_CHANGED,
                AuditService.OUTCOME_SUCCESS,
                null,
                "SYSTEM",
                "product-spu:" + spuId,
                null,
                "from=" + existing.status() + ",to=" + ProductStatus.RECYCLED);
        return CatalogDtoAssembler.toResponse(saved);
    }

    @WithSpan("catalog.get-spu")
    @Transactional(readOnly = true)
    public CatalogSpuResponseDto getSpu(Long spuId) {
        return CatalogDtoAssembler.toResponse(requireListedSpu(spuId));
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
    public CatalogPriceQuoteDto quotePrice(Long spuId, Long userId, String region) {
        CatalogSpu spu = requireListedSpu(spuId);
        PriceContext priceContext = priceContextResolver.resolve(userId, region);
        ProductPriceQuote quote = priceStrategy.quote(spu.priceBook(), priceContext);
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

    private CatalogSpu requireListedSpu(Long spuId) {
        return catalogStore
                .findSpuById(spuId)
                .filter(spu -> spu.status() == ProductStatus.LISTED)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SPU does not exist"));
    }

    private static List<SpecificationDimension> toDimensions(CatalogCreateSpuRequestDto request) {
        return request.specifications().stream()
                .map(ProductCatalogApplicationService::toDimension)
                .toList();
    }

    private static Long resolveShopId(CatalogCreateSpuRequestDto request) {
        return resolveShopId(request.shopId(), request.attributes());
    }

    private static Long resolveShopId(Long requestedShopId, Map<String, Object> attributes) {
        if (attributes != null && attributes.containsKey("shopId")) {
            Long legacyShopId = parseLegacyShopId(attributes.get("shopId"));
            if (legacyShopId == null || legacyShopId <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Catalog shopId must be a positive integer");
            }
            if (requestedShopId != null && !requestedShopId.equals(legacyShopId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Catalog shopId disagrees with legacy attributes");
            }
            requestedShopId = legacyShopId;
        }
        if (requestedShopId == null || requestedShopId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Catalog shopId is required");
        }
        return requestedShopId;
    }

    private static Long parseLegacyShopId(Object value) {
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).longValueExact();
            } catch (ArithmeticException | NumberFormatException exception) {
                return null;
            }
        }
        if (value instanceof String text && POSITIVE_LONG.matcher(text.trim()).matches()) {
            try {
                return Long.valueOf(text.trim());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private static SpecificationDimension toDimension(CatalogSpecificationDimensionDto dto) {
        return new SpecificationDimension(dto.name(), dto.values());
    }

    private static void validateSkuCount(List<SkuSpecification> specifications) {
        if (specifications.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "At least one SKU specification is required");
        }
        if (specifications.size() > MAX_SKU_COUNT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "SKU count exceeds catalog safety limit");
        }
    }

    private CatalogSpu requireManagementSpu(Long spuId) {
        return catalogStore
                .findSpuById(spuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SPU does not exist"));
    }

    private static ProductPriceBook priceBook(
            BigDecimal originalPrice,
            BigDecimal memberPrice,
            BigDecimal strikePrice,
            Map<String, BigDecimal> regionPrices) {
        return new ProductPriceBook(originalPrice, memberPrice, strikePrice, regionPrices);
    }

    private enum UnconfiguredImageReferenceService implements ImageReferenceService {
        INSTANCE;

        @Override
        public void retain(String imagePath) {
            if (ImageReferenceService.isTrackable(imagePath)) {
                throw new IllegalStateException("Catalog image reference tracking is not configured");
            }
        }

        @Override
        public void release(String imagePath) {
            throw new UnsupportedOperationException("Catalog image reference tracking is not configured");
        }

        @Override
        public long referenceCount(String imagePath) {
            throw new UnsupportedOperationException("Catalog image reference tracking is not configured");
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("Catalog image reference tracking is not configured");
        }
    }
}

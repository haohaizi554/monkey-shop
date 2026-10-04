package com.example.monkey.product.infrastructure;

import com.example.monkey.product.domain.CatalogSku;
import com.example.monkey.product.domain.CatalogSpu;
import com.example.monkey.product.domain.CatalogStore;
import com.example.monkey.product.domain.CatalogStore.CatalogPage;
import com.example.monkey.product.domain.CatalogStore.CatalogPageRequest;
import com.example.monkey.product.domain.CategoryNode;
import com.example.monkey.product.domain.ProductPriceBook;
import com.example.monkey.product.domain.SkuSpecification;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.catalog.store", havingValue = "jpa", matchIfMissing = true)
public class JpaCatalogStore implements CatalogStore {

    private static final String IMAGE_REFERENCE_CONFIGURATION_ERROR =
            "Image reference services are required for trackable image writes";
    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> SPEC_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, BigDecimal>> REGION_PRICE_TYPE = new TypeReference<>() {};

    private final ProductSpuRepository spuRepository;
    private final ProductSkuRepository skuRepository;
    private final ProductCategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;
    private final ImageReferenceService imageReferenceService;

    @Autowired
    public JpaCatalogStore(
            ProductSpuRepository spuRepository,
            ProductSkuRepository skuRepository,
            ProductCategoryRepository categoryRepository,
            ObjectMapper objectMapper,
            ImageReferenceService imageReferenceService) {
        this.spuRepository = spuRepository;
        this.skuRepository = skuRepository;
        this.categoryRepository = categoryRepository;
        this.objectMapper = objectMapper;
        this.imageReferenceService = imageReferenceService;
    }

    /** Compatibility constructor for direct mapping tests that do not execute image-tracked persistence. */
    public JpaCatalogStore(
            ProductSpuRepository spuRepository,
            ProductSkuRepository skuRepository,
            ProductCategoryRepository categoryRepository,
            ObjectMapper objectMapper) {
        this(spuRepository, skuRepository, categoryRepository, objectMapper, null);
    }

    @Override
    public CatalogSpu save(CatalogSpu spu) {
        Optional<ProductSpu> existing = spuRepository.findById(spu.id());
        requireImageTrackingConfigured(
                spu.imageUrl(), existing.map(ProductSpu::getImageUrl).orElse(null));
        ProductSpu entity = existing.orElseGet(() -> new ProductSpu(spu.id()));
        ProductPriceBook priceBook = spu.priceBook();
        entity.setCategoryId(spu.categoryId());
        entity.setShopId(spu.shopId());
        entity.setName(spu.name());
        entity.setTitle(spu.title());
        entity.setStatus(spu.status());
        entity.setOriginalPrice(priceBook.originalPrice());
        entity.setMemberPrice(priceBook.memberPrice());
        entity.setStrikePrice(priceBook.strikePrice());
        entity.setRegionPricesJson(write(priceBook.regionPrices()));
        entity.setAttributesJson(write(spu.attributes()));
        entity.setDetailJsonLd(spu.detailJsonLd());
        entity.setSupplierPrivateRemark(spu.supplierPrivateRemark());
        entity.setImageUrl(spu.imageUrl());
        ProductSpu savedSpu = spuRepository.save(entity);

        List<ProductSku> reconciledSkus;
        if (existing.isEmpty()) {
            skuRepository.saveAll(
                    spu.skus().stream().map(JpaCatalogStore::toEntity).toList());
            reconciledSkus = null;
        } else {
            reconciledSkus = reconcileExistingSkus(spu.id(), spu.skus());
        }
        List<ProductSku> savedSkus = reconciledSkus == null
                ? activeSkus(spu.id())
                : reconciledSkus.stream().filter(ProductSku::isActive).toList();
        return toDomain(savedSpu, savedSkus);
    }

    @Override
    public CatalogPage findManagementPage(CatalogPageRequest request) {
        PageRequest pageable = PageRequest.of(request.page(), request.size(), Sort.by(Sort.Order.desc("id")));
        Page<ProductSpu> page = spuRepository.findManagementPage(
                TenantContext.currentTenantIdOrDefault(), request.status(), request.keyword(), pageable);
        List<CatalogSpu> content = page.getContent().stream()
                .map(spu -> toDomain(spu, activeSkus(spu.getId())))
                .toList();
        return new CatalogPage(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    private void requireImageTrackingConfigured(String... imagePaths) {
        if (imageReferenceService != null) {
            return;
        }
        for (String imagePath : imagePaths) {
            if (ImageReferenceService.isTrackable(imagePath)) {
                throw new IllegalStateException(IMAGE_REFERENCE_CONFIGURATION_ERROR);
            }
        }
    }

    @Override
    public Optional<CatalogSpu> findSpuById(Long spuId) {
        return spuRepository.findById(spuId).map(spu -> toDomain(spu, activeSkus(spuId)));
    }

    @Override
    public boolean isLeafCategory(Long categoryId) {
        return categoryRepository
                .findById(categoryId)
                .filter(ProductCategory::isActive)
                .filter(category -> Integer.valueOf(3).equals(category.getLevel()))
                .isPresent();
    }

    @Override
    public List<CategoryNode> findCategoryTree() {
        Map<Long, List<ProductCategory>> byParent = new HashMap<>();
        for (ProductCategory category : categoryRepository.findByActiveTrueOrderByLevelAscSortOrderAscNameAsc()) {
            byParent.computeIfAbsent(category.getParentId(), ignored -> new ArrayList<>())
                    .add(category);
        }
        return toCategoryNodes(null, byParent);
    }

    private static ProductSku toEntity(CatalogSku sku) {
        ProductPriceBook priceBook = sku.priceBook();
        ProductSku entity = new ProductSku(sku.id());
        entity.setSpuId(sku.spuId());
        entity.setSkuCode(sku.skuCode());
        entity.setSpecJson(writeStatic(sku.specification().values()));
        entity.setOriginalPrice(priceBook.originalPrice());
        entity.setMemberPrice(priceBook.memberPrice());
        entity.setStrikePrice(priceBook.strikePrice());
        entity.setRegionPricesJson(writeStatic(priceBook.regionPrices()));
        entity.setActive(sku.active());
        return entity;
    }

    private List<ProductSku> activeSkus(Long spuId) {
        List<ProductSku> skus = skuRepository.findBySpuIdAndActiveTrueOrderByIdAsc(spuId);
        return skus == null ? List.of() : skus;
    }

    private List<ProductSku> reconcileExistingSkus(Long spuId, List<CatalogSku> incomingSkus) {
        List<ProductSku> persistedSkus = skuRepository.findBySpuIdOrderByIdAsc(spuId);
        if (persistedSkus == null || persistedSkus.isEmpty()) {
            // Compatibility with direct mapping tests and older adapters that expose active rows only.
            persistedSkus = activeSkus(spuId);
        }
        Map<Long, ProductSku> byId = new HashMap<>();
        Map<String, ProductSku> byCode = new HashMap<>();
        for (ProductSku persisted : persistedSkus) {
            byId.put(persisted.getId(), persisted);
            byCode.put(persisted.getSkuCode(), persisted);
        }

        Set<ProductSku> matched = new HashSet<>();
        List<ProductSku> reconciled = new ArrayList<>();
        boolean changed = false;
        for (CatalogSku incoming : incomingSkus) {
            ProductSku persisted = byId.get(incoming.id());
            if (persisted == null || matched.contains(persisted)) {
                persisted = byCode.get(incoming.skuCode());
            }
            if (persisted == null || matched.contains(persisted)) {
                persisted = toEntity(incoming);
                changed = true;
            } else {
                changed |= updateEntity(persisted, incoming);
                matched.add(persisted);
            }
            reconciled.add(persisted);
        }
        for (ProductSku persisted : persistedSkus) {
            if (!matched.contains(persisted) && persisted.isActive()) {
                persisted.setActive(false);
                changed = true;
            }
            if (!reconciled.contains(persisted)) {
                reconciled.add(persisted);
            }
        }
        if (changed) {
            skuRepository.saveAll(reconciled);
        }
        return reconciled;
    }

    private static boolean updateEntity(ProductSku entity, CatalogSku sku) {
        ProductPriceBook priceBook = sku.priceBook();
        String specJson = writeStatic(sku.specification().values());
        String regionPricesJson = writeStatic(priceBook.regionPrices());
        boolean changed = !Objects.equals(entity.getSpuId(), sku.spuId())
                || !Objects.equals(entity.getSkuCode(), sku.skuCode())
                || !Objects.equals(entity.getSpecJson(), specJson)
                || !Objects.equals(entity.getOriginalPrice(), priceBook.originalPrice())
                || !Objects.equals(entity.getMemberPrice(), priceBook.memberPrice())
                || !Objects.equals(entity.getStrikePrice(), priceBook.strikePrice())
                || !Objects.equals(entity.getRegionPricesJson(), regionPricesJson)
                || entity.isActive() != sku.active();
        entity.setSpuId(sku.spuId());
        entity.setSkuCode(sku.skuCode());
        entity.setSpecJson(specJson);
        entity.setOriginalPrice(priceBook.originalPrice());
        entity.setMemberPrice(priceBook.memberPrice());
        entity.setStrikePrice(priceBook.strikePrice());
        entity.setRegionPricesJson(regionPricesJson);
        entity.setActive(sku.active());
        return changed;
    }

    private CatalogSpu toDomain(ProductSpu spu, List<ProductSku> skus) {
        ProductPriceBook priceBook = new ProductPriceBook(
                spu.getOriginalPrice(),
                spu.getMemberPrice(),
                spu.getStrikePrice(),
                read(spu.getRegionPricesJson(), REGION_PRICE_TYPE));
        return new CatalogSpu(
                spu.getId(),
                spu.getCategoryId(),
                spu.getShopId(),
                spu.getName(),
                spu.getTitle(),
                spu.getStatus(),
                priceBook,
                read(spu.getAttributesJson(), ATTRIBUTES_TYPE),
                spu.getDetailJsonLd(),
                spu.getSupplierPrivateRemark(),
                spu.getImageUrl(),
                skus.stream().map(this::toDomain).toList());
    }

    private CatalogSku toDomain(ProductSku sku) {
        ProductPriceBook priceBook = new ProductPriceBook(
                sku.getOriginalPrice(),
                sku.getMemberPrice(),
                sku.getStrikePrice(),
                read(sku.getRegionPricesJson(), REGION_PRICE_TYPE));
        return new CatalogSku(
                sku.getId(),
                sku.getSpuId(),
                sku.getSkuCode(),
                new SkuSpecification(read(sku.getSpecJson(), SPEC_TYPE)),
                priceBook,
                sku.isActive());
    }

    private static List<CategoryNode> toCategoryNodes(
            Long parentId, Map<Long, List<ProductCategory>> categoriesByParent) {
        return categoriesByParent.getOrDefault(parentId, List.of()).stream()
                .map(category -> new CategoryNode(
                        category.getId(),
                        category.getParentId(),
                        category.getLevel(),
                        category.getCode(),
                        category.getName(),
                        toCategoryNodes(category.getId(), categoriesByParent)))
                .toList();
    }

    private String write(Object value) {
        return writeWithMapper(objectMapper, value);
    }

    private static String writeStatic(Object value) {
        ObjectMapper mapper = new ObjectMapper();
        return writeWithMapper(mapper, value);
    }

    private static String writeWithMapper(ObjectMapper mapper, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Catalog JSON cannot be serialized", exception);
        }
    }

    private <T> Map<String, T> read(String json, TypeReference<Map<String, T>> type) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Catalog JSON cannot be deserialized", exception);
        }
    }
}

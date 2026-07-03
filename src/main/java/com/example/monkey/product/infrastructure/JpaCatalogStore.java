package com.example.monkey.product.infrastructure;

import com.example.monkey.product.domain.CatalogSku;
import com.example.monkey.product.domain.CatalogSpu;
import com.example.monkey.product.domain.CatalogStore;
import com.example.monkey.product.domain.CategoryNode;
import com.example.monkey.product.domain.ProductPriceBook;
import com.example.monkey.product.domain.SkuSpecification;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.catalog.store", havingValue = "jpa", matchIfMissing = true)
public class JpaCatalogStore implements CatalogStore {

    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> SPEC_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, BigDecimal>> REGION_PRICE_TYPE = new TypeReference<>() {};

    private final ProductSpuRepository spuRepository;
    private final ProductSkuRepository skuRepository;
    private final ProductCategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;

    public JpaCatalogStore(
            ProductSpuRepository spuRepository,
            ProductSkuRepository skuRepository,
            ProductCategoryRepository categoryRepository,
            ObjectMapper objectMapper) {
        this.spuRepository = spuRepository;
        this.skuRepository = skuRepository;
        this.categoryRepository = categoryRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public CatalogSpu save(CatalogSpu spu) {
        ProductSpu entity = spuRepository.findById(spu.id()).orElseGet(() -> new ProductSpu(spu.id()));
        ProductPriceBook priceBook = spu.priceBook();
        entity.setCategoryId(spu.categoryId());
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

        skuRepository.deleteBySpuId(spu.id());
        skuRepository.flush();
        skuRepository.saveAll(spu.skus().stream().map(JpaCatalogStore::toEntity).toList());
        List<ProductSku> savedSkus = skuRepository.findBySpuIdAndActiveTrueOrderByIdAsc(spu.id());
        return toDomain(savedSpu, savedSkus);
    }

    @Override
    public Optional<CatalogSpu> findSpuById(Long spuId) {
        return spuRepository
                .findById(spuId)
                .map(spu -> toDomain(spu, skuRepository.findBySpuIdAndActiveTrueOrderByIdAsc(spuId)));
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

    private CatalogSpu toDomain(ProductSpu spu, List<ProductSku> skus) {
        ProductPriceBook priceBook = new ProductPriceBook(
                spu.getOriginalPrice(),
                spu.getMemberPrice(),
                spu.getStrikePrice(),
                read(spu.getRegionPricesJson(), REGION_PRICE_TYPE));
        return new CatalogSpu(
                spu.getId(),
                spu.getCategoryId(),
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

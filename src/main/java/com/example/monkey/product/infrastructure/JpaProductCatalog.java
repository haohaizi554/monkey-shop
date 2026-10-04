package com.example.monkey.product.infrastructure;

import com.example.monkey.product.domain.ProductCatalog;
import com.example.monkey.product.domain.ProductCatalog.ProductPage;
import com.example.monkey.product.domain.ProductCatalog.ProductPageRequest;
import com.example.monkey.product.domain.ProductCatalog.ProductRecord;
import com.example.monkey.product.domain.ProductCatalog.SortOrder.Direction;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.infrastructure.persistence.JpaPageRequests;
import com.example.monkey.shared.infrastructure.persistence.JpaSorts;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
public class JpaProductCatalog implements ProductCatalog {

    private static final String IMAGE_REFERENCE_CONFIGURATION_ERROR =
            "Image reference services are required for trackable image writes";
    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("id", "name", "breed", "price", "stock");

    private final MonkeyRepository monkeyRepository;
    private final ImageReferenceService imageReferenceService;

    @Autowired
    public JpaProductCatalog(MonkeyRepository monkeyRepository, ImageReferenceService imageReferenceService) {
        this.monkeyRepository = monkeyRepository;
        this.imageReferenceService = imageReferenceService;
    }

    /** Compatibility constructor for direct mapping tests that do not execute image-tracked persistence. */
    public JpaProductCatalog(MonkeyRepository monkeyRepository) {
        this(monkeyRepository, null);
    }

    @Override
    public ProductPage findPage(ProductPageRequest request) {
        Pageable pageable = toPageable(request);
        Page<Monkey> entities = request.hasFilters()
                ? monkeyRepository.findPage(
                        request.keyword(), request.minPrice(), request.maxPrice(), request.inStock(), pageable)
                : monkeyRepository.findAllBy(pageable);
        Page<ProductRecord> page = entities.map(JpaProductCatalog::toRecord);
        return new ProductPage(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    @Override
    public ProductRecord save(ProductRecord product) {
        Monkey existing = product.id() == null ? null : monkeyRepository.findById(product.id()).orElse(null);
        requireImageTrackingConfigured(
                product.imageUrl(), imageReferenceService == null && existing != null ? existing.getImageUrl() : null);
        Monkey entity = toEntity(product);
        if (existing != null) {
            entity.setVersion(existing.getVersion());
        }
        return toRecord(monkeyRepository.save(entity));
    }

    @Override
    public Optional<ProductRecord> findById(Long id) {
        return monkeyRepository.findById(id).map(JpaProductCatalog::toRecord);
    }

    @Override
    public void deleteById(Long id) {
        requireImageTrackingConfigured(
                imageReferenceService == null && id != null
                        ? monkeyRepository.findById(id).map(Monkey::getImageUrl).orElse(null)
                        : null);
        monkeyRepository.deleteById(id);
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

    private static Pageable toPageable(ProductPageRequest request) {
        List<Sort.Order> orders = request.sortOrders().stream()
                .flatMap(
                        order -> JpaSorts.allowedOrder(
                                order.property(), toSpringDirection(order.direction()), ALLOWED_SORT_PROPERTIES)
                                .stream())
                .toList();
        Sort sort = orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
        return JpaPageRequests.bounded(request.page(), request.size(), sort);
    }

    private static Sort.Direction toSpringDirection(Direction direction) {
        return direction == Direction.DESC ? Sort.Direction.DESC : Sort.Direction.ASC;
    }

    private static ProductRecord toRecord(Monkey monkey) {
        return new ProductRecord(
                monkey.getId(),
                monkey.getName(),
                monkey.getBreed(),
                monkey.getPrice(),
                monkey.getDescription(),
                monkey.getImageUrl(),
                monkey.getStock());
    }

    private static Monkey toEntity(ProductRecord product) {
        return new Monkey(
                product.id(),
                product.name(),
                product.breed(),
                product.price(),
                product.description(),
                product.imageUrl(),
                product.stock());
    }
}

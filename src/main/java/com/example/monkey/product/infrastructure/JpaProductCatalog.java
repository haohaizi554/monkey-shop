package com.example.monkey.product.infrastructure;

import com.example.monkey.product.domain.ProductCatalog;
import com.example.monkey.product.domain.ProductCatalog.ProductPage;
import com.example.monkey.product.domain.ProductCatalog.ProductPageRequest;
import com.example.monkey.product.domain.ProductCatalog.ProductRecord;
import com.example.monkey.product.domain.ProductCatalog.SortOrder.Direction;
import com.example.monkey.shared.infrastructure.persistence.JpaPageRequests;
import com.example.monkey.shared.infrastructure.persistence.JpaSorts;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
public class JpaProductCatalog implements ProductCatalog {

    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of("id", "name", "breed", "price", "stock");

    private final MonkeyRepository monkeyRepository;

    public JpaProductCatalog(MonkeyRepository monkeyRepository) {
        this.monkeyRepository = monkeyRepository;
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
        return toRecord(monkeyRepository.save(toEntity(product)));
    }

    @Override
    public Optional<ProductRecord> findById(Long id) {
        return monkeyRepository.findById(id).map(JpaProductCatalog::toRecord);
    }

    @Override
    public void deleteById(Long id) {
        monkeyRepository.deleteById(id);
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

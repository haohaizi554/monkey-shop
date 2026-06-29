package com.example.monkey.infrastructure.product;

import com.example.monkey.domain.product.ProductCatalog;
import com.example.monkey.domain.product.ProductCatalog.ProductPage;
import com.example.monkey.domain.product.ProductCatalog.ProductPageRequest;
import com.example.monkey.domain.product.ProductCatalog.ProductRecord;
import com.example.monkey.domain.product.ProductCatalog.SortOrder.Direction;
import com.example.monkey.entity.Monkey;
import com.example.monkey.repository.MonkeyRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
public class JpaProductCatalog implements ProductCatalog {

    private final MonkeyRepository monkeyRepository;

    public JpaProductCatalog(MonkeyRepository monkeyRepository) {
        this.monkeyRepository = monkeyRepository;
    }

    @Override
    public List<ProductRecord> findAll() {
        return monkeyRepository.findAll().stream()
                .map(JpaProductCatalog::toRecord)
                .toList();
    }

    @Override
    public ProductPage findPage(ProductPageRequest request) {
        Page<ProductRecord> page =
                monkeyRepository.findAllBy(toPageable(request)).map(JpaProductCatalog::toRecord);
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
                .map(order -> new Sort.Order(toSpringDirection(order.direction()), order.property()))
                .toList();
        Sort sort = orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
        return PageRequest.of(request.page(), request.size(), sort);
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

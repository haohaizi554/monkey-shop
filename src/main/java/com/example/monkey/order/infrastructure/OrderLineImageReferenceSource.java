package com.example.monkey.order.infrastructure;

import com.example.monkey.shared.domain.storage.StoredImageReferenceSource;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(305)
public class OrderLineImageReferenceSource implements StoredImageReferenceSource {

    private final OrderLineRepository orderLineRepository;
    private final int referenceScanBatchSize;

    public OrderLineImageReferenceSource(
            OrderLineRepository orderLineRepository,
            @Value("${app.upload.cleanup.reference-scan-batch-size:500}") int referenceScanBatchSize) {
        this.orderLineRepository = orderLineRepository;
        this.referenceScanBatchSize = Math.max(1, referenceScanBatchSize);
    }

    @Override
    public boolean isUsed(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return false;
        }
        String canonicalPath = imagePath.trim();
        return orderLineRepository.countByProductImage(canonicalPath) > 0
                || orderLineRepository.countByProductImageStartingWith(canonicalPath + "@") > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public void forEachReferencedImagePath(Consumer<String> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        scan(orderLineRepository::findProductImages, consumer);
    }

    private void scan(Function<Pageable, List<String>> readPage, Consumer<String> consumer) {
        int pageNumber = 0;
        while (true) {
            List<String> values = readPage.apply(PageRequest.of(pageNumber, referenceScanBatchSize));
            if (values == null || values.isEmpty()) {
                return;
            }
            values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(consumer);
            if (values.size() < referenceScanBatchSize) {
                return;
            }
            pageNumber++;
        }
    }
}

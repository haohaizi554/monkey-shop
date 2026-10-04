package com.example.monkey.cart.infrastructure;

import com.example.monkey.shared.domain.storage.StoredImageReferenceSource;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(400)
public class CartCheckoutImageReferenceSource implements StoredImageReferenceSource {

    private final CartCheckoutLineRepository lineRepository;
    private final int referenceScanBatchSize;

    public CartCheckoutImageReferenceSource(
            CartCheckoutLineRepository lineRepository,
            @Value("${app.upload.cleanup.reference-scan-batch-size:500}") int referenceScanBatchSize) {
        this.lineRepository = lineRepository;
        this.referenceScanBatchSize = Math.max(1, referenceScanBatchSize);
    }

    @Override
    public boolean isUsed(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return false;
        }
        String canonicalPath = imagePath.trim();
        return lineRepository.countByProductImage(canonicalPath) > 0
                || lineRepository.countByProductImageStartingWith(canonicalPath + "@") > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public void forEachReferencedImagePath(Consumer<String> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        int pageNumber = 0;
        while (true) {
            List<String> values = lineRepository.findProductImages(PageRequest.of(pageNumber, referenceScanBatchSize));
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

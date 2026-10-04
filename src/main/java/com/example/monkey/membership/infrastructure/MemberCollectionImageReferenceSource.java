package com.example.monkey.membership.infrastructure;

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
@Order(410)
public class MemberCollectionImageReferenceSource implements StoredImageReferenceSource {

    private final MemberCollectionRepository collectionRepository;
    private final int referenceScanBatchSize;

    public MemberCollectionImageReferenceSource(
            MemberCollectionRepository collectionRepository,
            @Value("${app.upload.cleanup.reference-scan-batch-size:500}") int referenceScanBatchSize) {
        this.collectionRepository = collectionRepository;
        this.referenceScanBatchSize = Math.max(1, referenceScanBatchSize);
    }

    @Override
    public boolean isUsed(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) {
            return false;
        }
        String canonicalPath = imagePath.trim();
        return collectionRepository.countByProductImage(canonicalPath) > 0
                || collectionRepository.countByProductImageStartingWith(canonicalPath + "@") > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public void forEachReferencedImagePath(Consumer<String> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        int pageNumber = 0;
        while (true) {
            List<String> values =
                    collectionRepository.findProductImages(PageRequest.of(pageNumber, referenceScanBatchSize));
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

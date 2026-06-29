package com.example.monkey.user.infrastructure;

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
@Order(200)
public class UserImageReferenceSource implements StoredImageReferenceSource {

    private final UserRepository userRepository;
    private final int referenceScanBatchSize;

    public UserImageReferenceSource(
            UserRepository userRepository,
            @Value("${app.upload.cleanup.reference-scan-batch-size:500}") int referenceScanBatchSize) {
        this.userRepository = userRepository;
        this.referenceScanBatchSize = Math.max(1, referenceScanBatchSize);
    }

    @Override
    public boolean isUsed(String imagePath) {
        return userRepository.countByAvatar(imagePath) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public void forEachReferencedImagePath(Consumer<String> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        scan(userRepository::findAvatars, consumer);
    }

    private void scan(Function<Pageable, List<String>> readPage, Consumer<String> consumer) {
        int pageNumber = 0;
        while (true) {
            List<String> values = readPage.apply(PageRequest.of(pageNumber, referenceScanBatchSize));
            if (values == null || values.isEmpty()) {
                return;
            }
            values.stream().filter(value -> value != null && !value.isBlank()).forEach(consumer);
            if (values.size() < referenceScanBatchSize) {
                return;
            }
            pageNumber++;
        }
    }
}

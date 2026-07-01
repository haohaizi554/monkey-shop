package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.domain.storage.ImageUsageChecker;
import com.example.monkey.shared.domain.storage.StoredImageReferenceSource;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CompositeImageUsageChecker implements ImageUsageChecker {

    private final List<StoredImageReferenceSource> referenceSources;

    public CompositeImageUsageChecker(List<StoredImageReferenceSource> referenceSources) {
        this.referenceSources = List.copyOf(referenceSources);
    }

    @Override
    public boolean isUsed(String imagePath) {
        return referenceSources.stream().anyMatch(source -> source.isUsed(imagePath));
    }
}

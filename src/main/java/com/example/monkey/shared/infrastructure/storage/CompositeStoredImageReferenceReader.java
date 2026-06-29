package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.domain.storage.StoredImageReferenceReader;
import com.example.monkey.shared.domain.storage.StoredImageReferenceSource;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class CompositeStoredImageReferenceReader implements StoredImageReferenceReader {

    private final List<StoredImageReferenceSource> referenceSources;

    public CompositeStoredImageReferenceReader(List<StoredImageReferenceSource> referenceSources) {
        this.referenceSources = List.copyOf(referenceSources);
    }

    @Override
    public void forEachReferencedImagePath(Consumer<String> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        referenceSources.forEach(source -> source.forEachReferencedImagePath(consumer));
    }
}

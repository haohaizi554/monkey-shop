package com.example.monkey.shared.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.domain.storage.StoredImageReferenceSource;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CompositeStoredImageReferenceReaderTest {

    @Test
    void readsStoredImageReferencesFromAllSources() {
        CompositeStoredImageReferenceReader reader = new CompositeStoredImageReferenceReader(List.of(
                new TestImageReferenceSource(List.of("/images/product/momo.png", "/images/avatar/user.png")),
                new TestImageReferenceSource(List.of("/images/product/momo.png", "/images/avatar/buyer.png"))));

        Set<String> imagePaths = reader.findAllReferencedImagePaths();

        assertThat(imagePaths)
                .containsExactly("/images/product/momo.png", "/images/avatar/user.png", "/images/avatar/buyer.png");
    }

    private record TestImageReferenceSource(List<String> imagePaths) implements StoredImageReferenceSource {

        @Override
        public boolean isUsed(String imagePath) {
            return imagePaths.contains(imagePath);
        }

        @Override
        public void forEachReferencedImagePath(Consumer<String> consumer) {
            imagePaths.forEach(consumer);
        }
    }
}

package com.example.monkey.shared.domain.storage;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

public interface StoredImageReferenceReader {

    void forEachReferencedImagePath(Consumer<String> consumer);

    default Set<String> findAllReferencedImagePaths() {
        Set<String> imagePaths = new LinkedHashSet<>();
        forEachReferencedImagePath(imagePaths::add);
        return imagePaths;
    }
}

package com.example.monkey.shared.domain.storage;

import java.util.function.Consumer;

public interface StoredImageReferenceSource {

    boolean isUsed(String imagePath);

    void forEachReferencedImagePath(Consumer<String> consumer);
}

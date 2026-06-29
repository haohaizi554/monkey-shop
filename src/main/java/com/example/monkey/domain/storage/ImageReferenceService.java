package com.example.monkey.domain.storage;

import java.util.Collection;

public interface ImageReferenceService {

    void retain(String imagePath);

    void release(String imagePath);

    long referenceCount(String imagePath);

    default boolean hasReferences(String imagePath) {
        return referenceCount(imagePath) > 0;
    }

    void rebuild(Collection<String> imagePaths);

    static boolean isTrackable(String imagePath) {
        return hasText(imagePath) && !imagePath.contains("default_product") && !imagePath.contains("default_avatar");
    }

    static boolean isLocalImagePath(String imagePath) {
        return hasText(imagePath) && imagePath.startsWith("/images/");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

package com.example.monkey.domain.storage;

import java.util.Set;

public interface StoredImageReferenceReader {

    Set<String> findAllReferencedImagePaths();
}

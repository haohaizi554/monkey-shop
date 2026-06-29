package com.example.monkey.shared.web;

import com.example.monkey.domain.storage.UploadFile;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

public final class MultipartUploadFile implements UploadFile {

    private final MultipartFile delegate;

    private MultipartUploadFile(MultipartFile delegate) {
        this.delegate = delegate;
    }

    public static UploadFile from(MultipartFile file) {
        return file == null ? null : new MultipartUploadFile(file);
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public long size() {
        return delegate.getSize();
    }

    @Override
    public InputStream openStream() throws IOException {
        return delegate.getInputStream();
    }
}

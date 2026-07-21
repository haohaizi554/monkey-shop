package com.example.monkey.tenant.application.dto;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public record TenantExportArtifactDto(InputStream content, long contentLength) implements AutoCloseable {

    public TenantExportArtifactDto {
        content = Objects.requireNonNull(content, "tenant export artifact content is required");
        if (contentLength < -1) {
            throw new IllegalArgumentException("tenant export artifact length is invalid");
        }
    }

    @Override
    public void close() throws IOException {
        content.close();
    }
}

package com.example.monkey.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.monkey.domain.storage.ObjectStorageService;
import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectStorageServiceTest {

    @TempDir
    Path uploadRoot;

    @Test
    void storesObjectUnderConfiguredRootAndReturnsLocalPublicUrl() throws Exception {
        LocalObjectStorageService storage = new LocalObjectStorageService(uploadRoot, "");

        ObjectStorageService.StoredObject stored =
                storage.store("/avatar/alice.png", new byte[] {1, 2, 3}, "image/png");

        assertThat(stored.objectKey()).isEqualTo("avatar/alice.png");
        assertThat(stored.publicUrl()).isEqualTo("/images/avatar/alice.png");
        assertThat(Files.readAllBytes(uploadRoot.resolve("avatar/alice.png"))).containsExactly(1, 2, 3);
    }

    @Test
    void rejectsTraversalKeysBeforeWriting() {
        LocalObjectStorageService storage = new LocalObjectStorageService(uploadRoot, "");

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> storage.store("../evil.png", new byte[] {1}, "image/png"))
                .withMessage("invalid object key")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void createsLocalPresignedPostFallbackWithPolicyFields() {
        LocalObjectStorageService storage = new LocalObjectStorageService(uploadRoot, "");
        Instant before = Instant.now();

        ObjectStorageService.PresignedPostForm form =
                storage.createPresignedPost("/product/item.png", "image/png", 1024L, Duration.ofMinutes(10));

        assertThat(form.objectKey()).isEqualTo("product/item.png");
        assertThat(form.uploadUrl()).isEqualTo("/api/upload");
        assertThat(form.publicUrl()).isEqualTo("/images/product/item.png");
        assertThat(form.formData())
                .containsEntry("key", "product/item.png")
                .containsEntry("Content-Type", "image/png")
                .containsEntry("max-size", "1024");
        assertThat(form.expiresAt()).isAfterOrEqualTo(before.plus(Duration.ofMinutes(10)));
    }

    @Test
    void usesConfiguredPublicBaseUrlForGetAndPublicUrls() {
        LocalObjectStorageService storage =
                new LocalObjectStorageService(uploadRoot, "https://cdn.example.test/assets/");

        ObjectStorageService.PresignedGetUrl url =
                storage.createPresignedGetUrl("/product/item.png", Duration.ofMinutes(5));

        assertThat(storage.publicUrl("product/item.png")).isEqualTo("https://cdn.example.test/assets/product/item.png");
        assertThat(url.objectKey()).isEqualTo("product/item.png");
        assertThat(url.url()).isEqualTo("https://cdn.example.test/assets/product/item.png");
    }
}

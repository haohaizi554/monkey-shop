package com.example.monkey.shared.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.storage.ObjectStorageService;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import io.minio.Result;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class MinioObjectStorageServiceTest {

    @Test
    void storesObjectAndReturnsConfiguredPublicUrl() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioObjectStorageService storage = storage(minioClient, "https://cdn.example.test/assets/");

        ObjectStorageService.StoredObject stored =
                storage.store("/avatar/alice.png", new byte[] {1, 2, 3}, "image/png");

        assertThat(stored.objectKey()).isEqualTo("avatar/alice.png");
        assertThat(stored.publicUrl()).isEqualTo("https://cdn.example.test/assets/avatar/alice.png");
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void createsPresignedGetUrlWithExpiry() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("https://minio.example.test/signed-get");
        MinioObjectStorageService storage = storage(minioClient, "");
        Instant before = Instant.now();

        ObjectStorageService.PresignedGetUrl url =
                storage.createPresignedGetUrl("/product/item.png", Duration.ofMinutes(5));

        assertThat(url.objectKey()).isEqualTo("product/item.png");
        assertThat(url.url()).isEqualTo("https://minio.example.test/signed-get");
        assertThat(url.expiresAt()).isAfterOrEqualTo(before.plus(Duration.ofMinutes(5)));
    }

    @Test
    void createsPresignedPostFormWithUploadAndPublicUrls() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.getPresignedPostFormData(any(PostPolicy.class)))
                .thenReturn(Map.of("policy", "abc", "signature", "def"));
        MinioObjectStorageService storage = storage(minioClient, "https://cdn.example.test/assets");

        ObjectStorageService.PresignedPostForm form =
                storage.createPresignedPost("/product/item.png", "image/png", 1024L, Duration.ofMinutes(10));

        assertThat(form.objectKey()).isEqualTo("product/item.png");
        assertThat(form.uploadUrl()).isEqualTo("https://minio.example.test/bucket");
        assertThat(form.publicUrl()).isEqualTo("https://cdn.example.test/assets/product/item.png");
        assertThat(form.formData()).containsEntry("policy", "abc").containsEntry("signature", "def");
    }

    @Test
    void fallsBackToEndpointPublicUrlWhenCdnUrlIsBlank() {
        MinioObjectStorageService storage = storage(mock(MinioClient.class), " ");

        assertThat(storage.publicUrl("/product/item.png"))
                .isEqualTo("https://minio.example.test/bucket/product/item.png");
    }

    @Test
    void wrapsStoragePutFailuresAsIoException() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(new IllegalStateException("boom"));
        MinioObjectStorageService storage = storage(minioClient, "");

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> storage.store("avatar/alice.png", new byte[] {1}, "image/png"))
                .withMessage("object storage put failed")
                .withCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolvesOnlyManagedReferencesChecksExistenceAndListsBothImagePrefixes() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn("product/item.png");
        when(item.isDir()).thenReturn(false);
        when(item.lastModified()).thenReturn(ZonedDateTime.parse("2026-01-01T00:00:00Z"));
        @SuppressWarnings("unchecked")
        Result<Item> result = mock(Result.class);
        when(result.get()).thenReturn(item);
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(result));
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(mock(StatObjectResponse.class));
        MinioObjectStorageService storage = storage(minioClient, "https://cdn.example.test/assets/");

        assertThat(storage.resolveObjectKey("https://cdn.example.test/assets/product/item.png"))
                .isEqualTo("product/item.png");
        assertThat(storage.resolveObjectKey("https://unrelated.example.test/product/item.png")).isNull();
        assertThat(storage.exists("product/item.png")).isTrue();
        assertThat(storage.listStoredObjects())
                .extracting(ObjectStorageService.StoredObjectMetadata::objectKey)
                .containsExactly("product/item.png", "product/item.png");
        verify(minioClient).statObject(any(StatObjectArgs.class));
        verify(minioClient, org.mockito.Mockito.times(2)).listObjects(any(ListObjectsArgs.class));
    }

    @Test
    void wrapsStorageListingAndStatFailuresAsIoExceptions() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.listObjects(any(ListObjectsArgs.class))).thenThrow(new IllegalStateException("list failed"));
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(new IllegalStateException("stat failed"));
        MinioObjectStorageService storage = storage(minioClient, "");

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(storage::listStoredObjects)
                .withMessage("object storage list failed")
                .withCauseInstanceOf(IllegalStateException.class);
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> storage.exists("product/item.png"))
                .withMessage("object storage stat failed")
                .withCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void delegatesObjectDeletionToMinio() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        MinioObjectStorageService storage = storage(minioClient, "");

        assertThat(storage.delete("avatar/alice.png")).isTrue();

        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    private static MinioObjectStorageService storage(MinioClient minioClient, String publicBaseUrl) {
        return new MinioObjectStorageService(minioClient, "bucket", publicBaseUrl, "https://minio.example.test/");
    }
}

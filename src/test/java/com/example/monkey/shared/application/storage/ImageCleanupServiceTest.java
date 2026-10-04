package com.example.monkey.shared.application.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.domain.storage.ImageUsageChecker;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import com.example.monkey.shared.infrastructure.storage.InMemoryImageReferenceService;
import com.example.monkey.shared.infrastructure.storage.LocalObjectStorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class ImageCleanupServiceTest {

    @TempDir
    Path uploadRoot;

    private InMemoryImageReferenceService imageReferenceService;
    private TestImageUsageChecker imageUsageChecker;
    private ObjectStorageService objectStorageService;
    private ImageVariantService imageVariantService;
    private ImageCleanupService imageCleanupService;

    @BeforeEach
    void setUp() {
        imageReferenceService = new InMemoryImageReferenceService();
        imageReferenceService.replace(List.of());
        imageUsageChecker = new TestImageUsageChecker();
        objectStorageService = new LocalObjectStorageService(uploadRoot.toString(), "");
        imageVariantService = new ImageVariantService(objectStorageService, true, "webp,avif", "320,640", false);
        imageCleanupService = new ImageCleanupService(
                imageReferenceService, imageUsageChecker, objectStorageService, imageVariantService);
    }

    @Test
    void deletesUnreferencedImageInsideConfiguredRoot() throws IOException {
        Path image = uploadRoot.resolve("avatar/test.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");

        imageCleanupService.tryDelete("/images/avatar/test.png");

        assertThat(image).doesNotExist();
    }

    @Test
    void compatibilityLocalConstructorStillDeletesManagedImagesSafely() throws IOException {
        Path image = uploadRoot.resolve("avatar/compatibility.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        ImageCleanupService compatibilityCleanup =
                new ImageCleanupService(imageReferenceService, imageUsageChecker, uploadRoot.toString());

        compatibilityCleanup.tryDelete("/images/avatar/compatibility.png");

        assertThat(image).doesNotExist();
    }

    @Test
    void deletesGeneratedVariantSiblingsWithCanonicalImage() throws IOException {
        Path image = uploadRoot.resolve("avatar/test.png");
        Path variant = uploadRoot.resolve("avatar/test.png@320w.webp");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        Files.writeString(variant, "variant");

        imageCleanupService.tryDelete("/images/avatar/test.png");

        assertThat(image).doesNotExist();
        assertThat(variant).doesNotExist();
    }

    @Test
    void keepsImageWhenReferenceCountIsPresent() throws IOException {
        Path image = uploadRoot.resolve("avatar/kept.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        imageReferenceService.retain("/images/avatar/kept.png");

        imageCleanupService.tryDelete("/images/avatar/kept.png");

        assertThat(image).isRegularFile();
    }

    @Test
    void keepsDefaultAssetsOutOfImmediateCleanup() throws IOException {
        Path image = uploadRoot.resolve("avatar/default_team.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");

        imageCleanupService.tryDelete("/images/avatar/default_team.png");

        assertThat(image).isRegularFile();
    }

    @Test
    void keepsVariantWhenCanonicalImageStillHasReferences() throws IOException {
        Path variant = uploadRoot.resolve("avatar/kept.png@320w.webp");
        Files.createDirectories(variant.getParent());
        Files.writeString(variant, "variant");
        imageReferenceService.retain("/images/avatar/kept.png");

        imageCleanupService.tryDelete("/images/avatar/kept.png@320w.webp");

        assertThat(variant).isRegularFile();
    }

    @Test
    void keepsImageWhenPersistedUsageExists() throws IOException {
        Path image = uploadRoot.resolve("avatar/kept.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        imageUsageChecker.markUsed("/images/avatar/kept.png");

        imageCleanupService.tryDelete("/images/avatar/kept.png");

        assertThat(image).isRegularFile();
    }

    @Test
    void checksPersistedUsageAgainstCanonicalVariantPath() throws IOException {
        Path variant = uploadRoot.resolve("avatar/kept.png@320w.webp");
        Files.createDirectories(variant.getParent());
        Files.writeString(variant, "variant");
        imageUsageChecker.markUsed("/images/avatar/kept.png");

        imageCleanupService.tryDelete("/images/avatar/kept.png@320w.webp");

        assertThat(variant).isRegularFile();
    }

    @Test
    void rechecksReferencesBeforeDeletingWhenImageIsRetainedDuringCleanup() throws Exception {
        Path image = uploadRoot.resolve("avatar/concurrent.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        RetainDuringReferenceCheckService concurrentReferences = new RetainDuringReferenceCheckService();
        ImageCleanupService cleanup = new ImageCleanupService(
                concurrentReferences, imageUsageChecker, objectStorageService, imageVariantService);

        Thread cleanupThread = new Thread(() -> cleanup.tryDelete("/images/avatar/concurrent.png"));
        cleanupThread.start();
        assertThat(concurrentReferences.firstReferenceCheck.await(5, TimeUnit.SECONDS))
                .isTrue();

        concurrentReferences.retain("/images/avatar/concurrent.png");
        cleanupThread.join(5_000L);

        assertThat(cleanupThread.isAlive()).isFalse();
        assertThat(image).isRegularFile();
    }

    @Test
    void refusesCleanupPathTraversalOutsideConfiguredRoot() throws IOException {
        Path outsideImage = uploadRoot.getParent().resolve("outside.png");
        Files.writeString(outsideImage, "image");
        String traversal = "/images/../outside.png";

        imageCleanupService.tryDelete(traversal);

        assertThat(outsideImage).isRegularFile();
    }

    @Test
    void ignoresExternalObjectStorageUrlsForLocalFilesystemCleanup() {
        imageCleanupService.tryDelete("https://cdn.example.test/avatar/alice.png");

        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void unmanagedExternalReferenceDoesNotClaimOrDelete() throws Exception {
        ImageReferenceService references = mock();
        ImageUsageChecker usageChecker = mock();
        ObjectStorageService objectStorage = mock();
        ImageVariantService variantService = mock();
        String externalUrl = "https://attacker.example.test/product/victim.png";
        ImageCleanupService cleanup = new ImageCleanupService(
                references, usageChecker, objectStorage, variantService, "minio", uploadRoot.toString());

        cleanup.tryDelete(externalUrl);

        verify(objectStorage).resolveObjectKey(externalUrl);
        verify(references, never()).deleteIfUnreferenced(any(), any());
        verify(objectStorage, never()).delete(any());
        verifyNoInteractions(variantService);
    }

    @Test
    void minioCleanupClaimsThePublicUrlAndDeletesTheRemoteObjectAndVariants() throws Exception {
        ImageReferenceService references = mock();
        ImageUsageChecker usageChecker = mock();
        ObjectStorageService objectStorage = mock();
        ImageVariantService variantService = mock();
        String publicUrl = "https://cdn.example.test/assets/avatar/alice.png";
        when(objectStorage.resolveObjectKey(publicUrl)).thenReturn("avatar/alice.png");
        doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<Boolean> deletion = invocation.getArgument(1);
                    return deletion.get();
                })
                .when(references)
                .deleteIfUnreferenced(eq(publicUrl), any());
        when(objectStorage.delete("avatar/alice.png")).thenReturn(true);
        ImageCleanupService cleanup = new ImageCleanupService(
                references, usageChecker, objectStorage, variantService, "minio", uploadRoot.toString());

        cleanup.tryDelete(publicUrl);

        verify(objectStorage).resolveObjectKey(publicUrl);
        verify(references).deleteIfUnreferenced(eq(publicUrl), any());
        verify(objectStorage).delete("avatar/alice.png");
        verify(variantService).deleteVariants("avatar/alice.png");
        assertThat(uploadRoot).isEmptyDirectory();
    }

    @Test
    void managedDirectVariantKeyResolvesAndDeletesCanonicalObjectAndVariants() throws Exception {
        ImageReferenceService references = mock();
        ImageUsageChecker usageChecker = mock();
        ObjectStorageService objectStorage = mock();
        ImageVariantService variantService = mock();
        String directVariantKey = "avatar/alice.png@320w.webp";
        when(objectStorage.resolveObjectKey(directVariantKey)).thenReturn(directVariantKey);
        doAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<Boolean> deletion = invocation.getArgument(1);
                    return deletion.get();
                })
                .when(references)
                .deleteIfUnreferenced(eq("avatar/alice.png"), any());
        when(objectStorage.delete("avatar/alice.png")).thenReturn(true);
        ImageCleanupService cleanup = new ImageCleanupService(
                references, usageChecker, objectStorage, variantService, "minio", uploadRoot.toString());

        cleanup.tryDelete(directVariantKey);

        verify(objectStorage).resolveObjectKey(directVariantKey);
        verify(references).deleteIfUnreferenced(eq("avatar/alice.png"), any());
        verify(objectStorage).delete("avatar/alice.png");
        verify(variantService).deleteVariants("avatar/alice.png");
    }

    @Test
    void directObjectKeyChecksPersistedUsageThroughTheProviderPublicReference() throws Exception {
        ImageReferenceService references = mock();
        ImageUsageChecker usageChecker = mock();
        ObjectStorageService objectStorage = mock();
        ImageVariantService variantService = mock();
        String directVariantKey = "avatar/kept.png@320w.webp";
        String canonicalPublicUrl = "https://cdn.example.test/assets/avatar/kept.png";
        when(objectStorage.resolveObjectKey(directVariantKey)).thenReturn(directVariantKey);
        when(objectStorage.publicUrl("avatar/kept.png")).thenReturn(canonicalPublicUrl);
        when(usageChecker.isUsed(canonicalPublicUrl)).thenReturn(true);
        ImageCleanupService cleanup = new ImageCleanupService(
                references, usageChecker, objectStorage, variantService, "minio", uploadRoot.toString());

        cleanup.tryDelete(directVariantKey);

        verify(usageChecker).isUsed(canonicalPublicUrl);
        verify(references, never()).deleteIfUnreferenced(any(), any());
        verify(objectStorage, never()).delete(any());
    }

    @Test
    void defersPhysicalDeletionUntilDatabaseTransactionCommits() throws IOException {
        Path image = uploadRoot.resolve("avatar/after-commit.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        beginTransactionSynchronization();

        try {
            imageCleanupService.tryDelete("/images/avatar/after-commit.png");

            assertThat(image).isRegularFile();
            TransactionSynchronizationUtils.triggerAfterCommit();
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(image).doesNotExist();
        } finally {
            endTransactionSynchronization();
        }
    }

    @Test
    void keepsPhysicalFileWhenDatabaseTransactionRollsBack() throws IOException {
        Path image = uploadRoot.resolve("avatar/rolled-back.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        beginTransactionSynchronization();

        try {
            imageCleanupService.tryDelete("/images/avatar/rolled-back.png");

            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(image).isRegularFile();
        } finally {
            endTransactionSynchronization();
        }
    }

    @Test
    void rechecksReferencesAfterCommitBeforePhysicalDeletion() throws IOException {
        String imagePath = "/images/avatar/retained-before-commit.png";
        Path image = uploadRoot.resolve("avatar/retained-before-commit.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        beginTransactionSynchronization();

        try {
            imageCleanupService.tryDelete(imagePath);
            imageReferenceService.retain(imagePath);

            TransactionSynchronizationUtils.triggerAfterCommit();
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(image).isRegularFile();
        } finally {
            endTransactionSynchronization();
        }
    }

    private static void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void endTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static final class TestImageUsageChecker implements ImageUsageChecker {

        private final Set<String> usedImagePaths = new HashSet<>();

        private void markUsed(String imagePath) {
            usedImagePaths.add(imagePath);
        }

        @Override
        public boolean isUsed(String imagePath) {
            return usedImagePaths.contains(imagePath);
        }
    }

    private static final class RetainDuringReferenceCheckService implements ImageReferenceService {

        private final Map<String, Long> counts = new java.util.concurrent.ConcurrentHashMap<>();
        private final CountDownLatch firstReferenceCheck = new CountDownLatch(1);
        private final CountDownLatch retainCompleted = new CountDownLatch(1);
        private int referenceChecks;

        @Override
        public void retain(String imagePath) {
            counts.merge(imagePath, 1L, Long::sum);
            retainCompleted.countDown();
        }

        @Override
        public void release(String imagePath) {
            counts.computeIfPresent(imagePath, (ignored, count) -> count <= 1L ? null : count - 1L);
        }

        @Override
        public long referenceCount(String imagePath) {
            return counts.getOrDefault(imagePath, 0L);
        }

        @Override
        public boolean hasReferences(String imagePath) {
            if (referenceChecks++ == 0) {
                firstReferenceCheck.countDown();
                await(retainCompleted);
                return false;
            }
            return referenceCount(imagePath) > 0L;
        }

        @Override
        public void clear() {
            counts.clear();
        }

        @Override
        public void replace(Collection<String> imagePaths) {
            clear();
            if (imagePaths != null) {
                imagePaths.forEach(this::retain);
            }
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for concurrent image retain");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for concurrent image retain", exception);
            }
        }
    }
}

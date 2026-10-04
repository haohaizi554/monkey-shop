package com.example.monkey.shared.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.order.infrastructure.OrderReviewImageReferenceSource;
import com.example.monkey.order.infrastructure.OrderReviewRepository;
import com.example.monkey.product.infrastructure.ProductSpuImageReferenceSource;
import com.example.monkey.product.infrastructure.ProductSpuRepository;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import com.example.monkey.shared.domain.storage.StoredImageReferenceSource;
import com.example.monkey.shared.domain.storage.StoredImageReferenceReader;
import com.example.monkey.tenant.domain.ActiveTenantReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class ImageTaskTest {

    @TempDir
    Path uploadRoot;

    @Mock
    private StoredImageReferenceReader storedImageReferenceReader;

    @Test
    void rebuildsReferenceCountsAndDeletesExpiredUnreferencedImages() throws Exception {
        Path referenced = writeOldImage("product/kept.png");
        Path referencedVariant = writeOldImage("product/kept.png@320w.webp");
        Path orphan = writeOldImage("product/orphan.png");
        Path orphanVariant = writeOldImage("product/orphan.png@320w.webp");
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        doAnswer(invocation -> {
                    Consumer<String> consumer = invocation.getArgument(0);
                    consumer.accept("/images/product/kept.png");
                    return null;
                })
                .when(storedImageReferenceReader)
                .forEachReferencedImagePath(any());
        ImageTask task = new ImageTask(
                storedImageReferenceReader, referenceService, uploadRoot.toString(), Duration.ofHours(24));

        task.cleanUpOrphanImages();

        assertThat(referenced).isRegularFile();
        assertThat(referencedVariant).isRegularFile();
        assertThat(orphan).doesNotExist();
        assertThat(orphanVariant).doesNotExist();
        assertThat(referenceService.referenceCount("/images/product/kept.png")).isEqualTo(1L);
    }

    @Test
    void rebuildPreservesMultiplicityWhenSeveralRowsReferenceTheSameImage() throws Exception {
        Path shared = writeOldImage("product/shared.png");
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        doAnswer(invocation -> {
                    Consumer<String> consumer = invocation.getArgument(0);
                    consumer.accept("/images/product/shared.png");
                    consumer.accept("/images/product/shared.png");
                    return null;
                })
                .when(storedImageReferenceReader)
                .forEachReferencedImagePath(any());
        ImageTask task = new ImageTask(
                storedImageReferenceReader, referenceService, uploadRoot.toString(), Duration.ofHours(24));

        task.cleanUpOrphanImages();
        referenceService.release("/images/product/shared.png");

        assertThat(referenceService.referenceCount("/images/product/shared.png")).isEqualTo(1L);
        assertThat(shared).isRegularFile();
    }

    @Test
    void mapsConfiguredProviderPublicUrlBackToItsLocalCleanupPath() throws Exception {
        Path referencedThroughCdn = writeOldImage("avatar/cdn-avatar.png");
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        LocalObjectStorageService storage =
                new LocalObjectStorageService(uploadRoot, "https://cdn.example.test/static/");
        doAnswer(invocation -> {
                    Consumer<String> consumer = invocation.getArgument(0);
                    consumer.accept("https://cdn.example.test/static/avatar/cdn-avatar.png");
                    return null;
                })
                .when(storedImageReferenceReader)
                .forEachReferencedImagePath(any());
        ImageTask task = new ImageTask(storedImageReferenceReader, referenceService, storage, Duration.ofHours(24));

        task.cleanUpOrphanImages();

        assertThat(referencedThroughCdn).isRegularFile();
        assertThat(referenceService.referenceCount("https://cdn.example.test/static/avatar/cdn-avatar.png"))
                .isEqualTo(1L);
    }

    @Test
    void usesCompleteProviderListingAndDeletesCanonicalAssetAndVariants() throws Exception {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        storage.addOldObject("product/orphan.png");
        storage.addOldObject("product/orphan.png@320w.webp");
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        ImageTask task = new ImageTask(storedImageReferenceReader, referenceService, storage, Duration.ofHours(24));

        task.cleanUpOrphanImages();

        assertThat(storage.deletedObjectKeys)
                .containsExactlyInAnyOrder("product/orphan.png", "product/orphan.png@320w.webp");
    }

    @Test
    void abortsWithoutPublishingOrDeletingWhenProviderListingFails() throws Exception {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        storage.addOldObject("product/orphan.png");
        storage.failListing = true;
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        referenceService.retain("/images/product/last-known-good.png");
        ImageTask task = new ImageTask(storedImageReferenceReader, referenceService, storage, Duration.ofHours(24));

        assertThatThrownBy(task::cleanUpOrphanImages).isInstanceOf(IllegalStateException.class);

        assertThat(storage.deletedObjectKeys).isEmpty();
        assertThat(referenceService.referenceCount("/images/product/last-known-good.png")).isEqualTo(1L);
    }

    @Test
    void abortsWithoutPublishingOrDeletingWhenProviderListingContainsUnmanagedObject() {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        storage.addOldObject("other/unmanaged.bin");
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        referenceService.retain("/images/product/last-known-good.png");
        ImageTask task = new ImageTask(storedImageReferenceReader, referenceService, storage, Duration.ofHours(24));

        assertThatThrownBy(task::cleanUpOrphanImages).isInstanceOf(IllegalStateException.class);

        assertThat(storage.deletedObjectKeys).isEmpty();
        assertThat(referenceService.referenceCount("/images/product/last-known-good.png")).isEqualTo(1L);
    }

    @Test
    void ignoresExternalUnrelatedUrlsWhenBuildingReferenceSnapshot() throws Exception {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        storage.addOldObject("product/orphan.png");
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        doAnswer(invocation -> {
                    Consumer<String> consumer = invocation.getArgument(0);
                    consumer.accept("https://unrelated.example.test/product/orphan.png");
                    return null;
                })
                .when(storedImageReferenceReader)
                .forEachReferencedImagePath(any());
        ImageTask task = new ImageTask(storedImageReferenceReader, referenceService, storage, Duration.ofHours(24));

        task.cleanUpOrphanImages();

        assertThat(storage.deletedObjectKeys).containsExactly("product/orphan.png");
    }

    @Test
    void retainsReferenceOwnedOnlyByAnotherRetainedTenantWithoutAmbientContext() throws Exception {
        Path retained = writeOldImage("product/tenant-two.png");
        List<Long> observedTenants = new ArrayList<>();
        doAnswer(invocation -> {
                    observedTenants.add(TenantContext.currentTenantIdOrDefault());
                    Consumer<String> consumer = invocation.getArgument(0);
                    if (TenantContext.currentTenantIdOrDefault() == 2L) {
                        consumer.accept("/images/product/tenant-two.png");
                    }
                    return null;
                })
                .when(storedImageReferenceReader)
                .forEachReferencedImagePath(any());

        ImageTask task = new ImageTask(
                storedImageReferenceReader,
                new InMemoryImageReferenceService(),
                tenantIterator(List.of(1L, 2L)),
                uploadRoot.toString(),
                Duration.ofHours(24));

        task.cleanUpOrphanImages();

        assertThat(retained).isRegularFile();
        assertThat(observedTenants).containsExactly(1L, 2L, 1L, 2L);
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void abortsBeforeDeletingAndPreservesLastKnownGoodReferencesWhenDiscoveryFails() throws Exception {
        Path retained = writeOldImage("product/kept.png");
        Path orphan = writeOldImage("product/orphan.png");
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        referenceService.retain("/images/product/kept.png");
        doAnswer(invocation -> {
                    if (TenantContext.currentTenantIdOrDefault() == 2L) {
                        throw new IllegalStateException("tenant scan failed");
                    }
                    return null;
                })
                .when(storedImageReferenceReader)
                .forEachReferencedImagePath(any());
        ImageTask task = new ImageTask(
                storedImageReferenceReader,
                referenceService,
                tenantIterator(List.of(1L, 2L)),
                uploadRoot.toString(),
                Duration.ofHours(24));

        assertThatThrownBy(task::cleanUpOrphanImages).isInstanceOf(IllegalStateException.class);

        assertThat(retained).isRegularFile();
        assertThat(orphan).isRegularFile();
        assertThat(referenceService.referenceCount("/images/product/kept.png")).isEqualTo(1L);
    }

    @Test
    void preservesLastKnownGoodReferencesWhenFilesystemScanFailsAfterDiscovery() throws Exception {
        Files.writeString(uploadRoot.resolve("product"), "not-a-directory");
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        referenceService.retain("/images/avatar/last-known-good.png");
        ImageTask task = new ImageTask(
                storedImageReferenceReader, referenceService, uploadRoot.toString(), Duration.ofHours(24));

        assertThatThrownBy(task::cleanUpOrphanImages).isInstanceOf(IllegalStateException.class);

        assertThat(referenceService.referenceCount("/images/avatar/last-known-good.png")).isEqualTo(1L);
    }

    @Test
    void abortsDeletionWhenAReferenceIsRetainedDuringDiscovery() throws Exception {
        Path retainedDuringDiscovery = writeOldImage("product/retained-during-discovery.png");
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        CountDownLatch discoveryStarted = new CountDownLatch(1);
        CountDownLatch retainCompleted = new CountDownLatch(1);
        AtomicInteger discoveryCalls = new AtomicInteger();
        doAnswer(invocation -> {
                    if (discoveryCalls.incrementAndGet() == 1) {
                        discoveryStarted.countDown();
                        assertThat(retainCompleted.await(5, TimeUnit.SECONDS)).isTrue();
                    }
                    return null;
                })
                .when(storedImageReferenceReader)
                .forEachReferencedImagePath(any());
        ImageTask task = new ImageTask(
                storedImageReferenceReader, referenceService, uploadRoot.toString(), Duration.ofHours(24));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> cleanup = executor.submit(task::cleanUpOrphanImages);
            assertThat(discoveryStarted.await(5, TimeUnit.SECONDS)).isTrue();

            referenceService.retain("/images/product/retained-during-discovery.png");
            retainCompleted.countDown();
            cleanup.get(5, TimeUnit.SECONDS);

            assertThat(retainedDuringDiscovery).isRegularFile();
            assertThat(referenceService.referenceCount("/images/product/retained-during-discovery.png"))
                    .isEqualTo(1L);
        } finally {
            retainCompleted.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rebuildIncludesModernSpuAndReviewImagesEvenWithoutLegacyMonkeyRows() throws Exception {
        ProductSpuRepository spuRepository = mock(ProductSpuRepository.class);
        OrderReviewRepository reviewRepository = mock(OrderReviewRepository.class);
        when(spuRepository.findImageUrls(PageRequest.of(0, 10)))
                .thenReturn(List.of("/images/product/spu.png"));
        when(reviewRepository.findImageUrls(PageRequest.of(0, 10)))
                .thenReturn(List.of(" /images/avatar/review-a.png\n/images/avatar/review-b.png "));

        StoredImageReferenceSource spuSource = new ProductSpuImageReferenceSource(spuRepository, 10);
        StoredImageReferenceSource reviewSource = new OrderReviewImageReferenceSource(reviewRepository, 10);
        StoredImageReferenceReader reader = new CompositeStoredImageReferenceReader(List.of(spuSource, reviewSource));
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        Path spu = writeOldImage("product/spu.png");
        Path reviewA = writeOldImage("avatar/review-a.png");
        Path reviewB = writeOldImage("avatar/review-b.png");
        ImageTask task = new ImageTask(reader, referenceService, uploadRoot.toString(), Duration.ofHours(24));

        task.cleanUpOrphanImages();

        assertThat(spu).isRegularFile();
        assertThat(reviewA).isRegularFile();
        assertThat(reviewB).isRegularFile();
        assertThat(referenceService.referenceCount("/images/product/spu.png")).isEqualTo(1L);
        assertThat(referenceService.referenceCount("/images/avatar/review-a.png")).isEqualTo(1L);
        assertThat(referenceService.referenceCount("/images/avatar/review-b.png")).isEqualTo(1L);
    }

    @Test
    void doesNotDeleteImageRetainedAfterDiscoveryBeforeSnapshotCas() throws Exception {
        Path retainedAfterSnapshot = writeOldImage("product/retained-after-snapshot.png");
        RetainBeforeSnapshotCasReferenceService referenceService =
                new RetainBeforeSnapshotCasReferenceService();
        ImageTask task = new ImageTask(
                storedImageReferenceReader, referenceService, uploadRoot.toString(), Duration.ofHours(24));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> cleanup = executor.submit(task::cleanUpOrphanImages);

            assertThat(referenceService.snapshotPublishRequested.await(5, TimeUnit.SECONDS))
                    .isTrue();
            referenceService.retain("/images/product/retained-after-snapshot.png");
            cleanup.get(5, TimeUnit.SECONDS);

            assertThat(retainedAfterSnapshot).isRegularFile();
            assertThat(referenceService.referenceCount("/images/product/retained-after-snapshot.png"))
                    .isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void doesNotDeleteAnyCandidateWhenLiveReferenceReadFailsBeforeDeletion() throws Exception {
        Path firstOrphan = writeOldImage("product/first-orphan.png");
        Path secondOrphan = writeOldImage("product/second-orphan.png");
        FailsOnSecondReferenceReadService referenceService = new FailsOnSecondReferenceReadService();
        ImageTask task = new ImageTask(
                storedImageReferenceReader, referenceService, uploadRoot.toString(), Duration.ofHours(24));

        assertThatThrownBy(task::cleanUpOrphanImages).isInstanceOf(IllegalStateException.class);

        assertThat(firstOrphan).isRegularFile();
        assertThat(secondOrphan).isRegularFile();
    }

    @Test
    void providerWithoutCompareAndSwapDoesNotPublishThroughPlainReplace() {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        storage.addOldObject("product/orphan.png");
        ReplaceOnlyReferenceService referenceService = new ReplaceOnlyReferenceService();
        ImageTask task = new ImageTask(storedImageReferenceReader, referenceService, storage, Duration.ofHours(24));

        task.cleanUpOrphanImages();

        assertThat(referenceService.replaceCalled).isFalse();
        assertThat(storage.deletedObjectKeys).isEmpty();
    }

    @Test
    void successfulCompleteSnapshotRunsDeletionStateMaintenance() {
        RecordingObjectStorageService storage = new RecordingObjectStorageService();
        RecordingMaintenanceReferenceService referenceService =
                new RecordingMaintenanceReferenceService();
        ImageTask task = new ImageTask(
                storedImageReferenceReader, referenceService, storage, Duration.ofHours(24));

        task.cleanUpOrphanImages();

        assertThat(referenceService.maintenanceCalls).isEqualTo(1);
        assertThat(referenceService.lastAuthoritativeReferences).isEmpty();
        assertThat(referenceService.lastExistingStorageReferences).isEmpty();
    }

    @Test
    void cleanupTaskUsesDistributedSchedulerLock() throws Exception {
        Method method = ImageTask.class.getDeclaredMethod("cleanUpOrphanImages");

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("monkeyshop.image.cleanup");
        assertThat(lock.lockAtMostFor()).isEqualTo("${app.upload.cleanup.lock-at-most-for:PT30M}");
        assertThat(lock.lockAtLeastFor()).isEqualTo("${app.upload.cleanup.lock-at-least-for:PT1M}");
    }

    @Test
    void rejectsANegativeCleanupGracePeriod() {
        assertThatThrownBy(() -> new ImageTask(
                        storedImageReferenceReader,
                        new InMemoryImageReferenceService(),
                        uploadRoot.toString(),
                        Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gracePeriod");
    }

    private Path writeOldImage(String relativePath) throws Exception {
        Path image = uploadRoot.resolve(relativePath);
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        Files.setLastModifiedTime(image, FileTime.from(Instant.now().minusSeconds(25L * 60L * 60L)));
        return image;
    }

    private static ActiveTenantIterator tenantIterator(List<Long> tenantIds) {
        ActiveTenantReader tenantReader = mock(ActiveTenantReader.class);
        when(tenantReader.findRetainedTenantIds()).thenReturn(tenantIds);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        return new ActiveTenantIterator(tenantReader, transactionManager);
    }

    private static final class RetainBeforeSnapshotCasReferenceService
            extends InMemoryImageReferenceService {

        private final CountDownLatch snapshotPublishRequested = new CountDownLatch(1);
        private final CountDownLatch retainCompleted = new CountDownLatch(1);

        @Override
        public boolean replaceIfUnchanged(
                Collection<String> imagePaths, long expectedVersion) {
            snapshotPublishRequested.countDown();
            await(retainCompleted);
            return super.replaceIfUnchanged(imagePaths, expectedVersion);
        }

        @Override
        public synchronized void retain(String imagePath) {
            super.retain(imagePath);
            retainCompleted.countDown();
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

    private static final class RecordingMaintenanceReferenceService
            extends InMemoryImageReferenceService {

        private int maintenanceCalls;
        private List<String> lastAuthoritativeReferences = List.of();
        private List<String> lastExistingStorageReferences = List.of();

        @Override
        public DeletionMaintenanceResult maintainDeletionState(
                Collection<String> authoritativeReferences,
                Collection<String> existingStorageReferences,
                long nowEpochMillis) {
            maintenanceCalls++;
            lastAuthoritativeReferences = List.copyOf(authoritativeReferences);
            lastExistingStorageReferences = List.copyOf(existingStorageReferences);
            return super.maintainDeletionState(
                    authoritativeReferences, existingStorageReferences, nowEpochMillis);
        }
    }

    private static final class FailsOnSecondReferenceReadService extends InMemoryImageReferenceService {

        private int referenceReads;

        @Override
        public synchronized long referenceCount(String imagePath) {
            if (++referenceReads == 2) {
                throw new IllegalStateException("redis unavailable");
            }
            return super.referenceCount(imagePath);
        }
    }

    private static final class ReplaceOnlyReferenceService implements ImageReferenceService {

        private boolean replaceCalled;

        @Override
        public void retain(String imagePath) {}

        @Override
        public void release(String imagePath) {}

        @Override
        public long referenceCount(String imagePath) {
            return 0L;
        }

        @Override
        public void clear() {}

        @Override
        public void replace(java.util.Collection<String> imagePaths) {
            replaceCalled = true;
        }
    }

    private static final class RecordingObjectStorageService implements ObjectStorageService {

        private final List<ObjectStorageService.StoredObjectMetadata> objects = new ArrayList<>();
        private final List<String> deletedObjectKeys = new ArrayList<>();
        private boolean failListing;

        private void addOldObject(String objectKey) {
            objects.add(new ObjectStorageService.StoredObjectMetadata(
                    objectKey, Instant.now().minus(Duration.ofDays(2))));
        }

        @Override
        public StoredObject store(String objectKey, byte[] content, String contentType) {
            return new StoredObject(objectKey, publicUrl(objectKey));
        }

        @Override
        public PresignedGetUrl createPresignedGetUrl(String objectKey, Duration ttl) {
            return new PresignedGetUrl(objectKey, publicUrl(objectKey), Instant.now().plus(ttl));
        }

        @Override
        public PresignedPostForm createPresignedPost(
                String objectKey, String contentType, long maxSizeBytes, Duration ttl) {
            return new PresignedPostForm(
                    objectKey, "/upload", java.util.Map.of(), publicUrl(objectKey), Instant.now().plus(ttl));
        }

        @Override
        public String publicUrl(String objectKey) {
            return "https://cdn.example.test/assets/" + objectKey;
        }

        @Override
        public String resolveObjectKey(String imageReference) {
            if (imageReference == null) {
                return null;
            }
            String prefix = "https://cdn.example.test/assets/";
            if (imageReference.startsWith(prefix)) {
                return imageReference.substring(prefix.length());
            }
            if (imageReference.startsWith("product/") || imageReference.startsWith("avatar/")) {
                return imageReference;
            }
            return null;
        }

        @Override
        public boolean exists(String objectKey) {
            return objects.stream().anyMatch(object -> object.objectKey().equals(objectKey));
        }

        @Override
        public List<StoredObjectMetadata> listStoredObjects() throws IOException {
            if (failListing) {
                throw new IOException("provider listing failed");
            }
            return List.copyOf(objects);
        }

        @Override
        public boolean delete(String objectKey) {
            deletedObjectKeys.add(objectKey);
            return true;
        }
    }
}

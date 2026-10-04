package com.example.monkey.shared.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InMemoryImageReferenceServiceTest {

    @Test
    void retainsReleasesAndRemovesCountsAtZero() {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService();

        service.retain("/images/product/item.png");
        service.retain("/images/product/item.png");
        service.release("/images/product/item.png");

        assertThat(service.referenceCount("/images/product/item.png")).isEqualTo(1L);

        service.release("/images/product/item.png");

        assertThat(service.referenceCount("/images/product/item.png")).isZero();
    }

    @Test
    void rebuildIgnoresDefaultAssetsAndBlankValues() {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService();
        service.retain("/images/product/stale.png");

        service.rebuild(List.of(
                "/images/product/item.png",
                "/images/product/item.png",
                "/images/default_product.png",
                "/images/product/default_catalog.png",
                " "));

        assertThat(service.referenceCount("/images/product/stale.png")).isZero();
        assertThat(service.referenceCount("/images/product/item.png")).isEqualTo(2L);
        assertThat(service.referenceCount("/images/default_product.png")).isZero();
        assertThat(service.referenceCount("/images/product/default_catalog.png"))
                .isZero();
        assertThat(service.referenceCount(" ")).isZero();
    }

    @Test
    void clearRemovesAllReferenceCounts() {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService();
        service.retain("/images/product/item.png");

        service.clear();

        assertThat(service.referenceCount("/images/product/item.png")).isZero();
    }

    @Test
    void deletionClaimPermanentlyPreventsTheSameImmutablePathFromBeingRetainedAgain() {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService();
        service.replace(List.of());

        assertThat(service.deleteIfUnreferenced("/images/product/deleted.png", () -> true))
                .isTrue();

        assertThatThrownBy(() -> service.retain("/images/product/deleted.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deleted");
        assertThat(service.deleteIfUnreferenced("/images/product/deleted.png", () -> true))
                .isFalse();
    }

    @Test
    void failedPhysicalDeleteKeepsTheFailClosedTombstone() {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService();
        service.replace(List.of());

        assertThat(service.deleteIfUnreferenced("/images/avatar/locked.png", () -> false))
                .isFalse();

        assertThatThrownBy(() -> service.retain("/images/avatar/locked.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deleted");
        assertThat(service.deleteIfUnreferenced("/images/avatar/locked.png", () -> true))
                .isTrue();
        assertThatThrownBy(() -> service.retain("/images/avatar/locked.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deleted");
    }

    @Test
    void variantReferencesUseTheCanonicalLogicalAssetKey() {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService();
        service.replace(List.of());

        service.retain("/images/avatar/photo.png@320w.webp");

        assertThat(service.referenceCount("/images/avatar/photo.png")).isEqualTo(1L);
        assertThat(service.deleteIfUnreferenced("/images/avatar/photo.png", () -> true))
                .isFalse();
    }

    @Test
    void slowPhysicalDeletionDoesNotBlockAnUnrelatedRetain() throws Exception {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService();
        service.replace(List.of());
        CountDownLatch deletionStarted = new CountDownLatch(1);
        CountDownLatch finishDeletion = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var deletion = executor.submit(() -> service.deleteIfUnreferenced("/images/avatar/slow.png", () -> {
                deletionStarted.countDown();
                await(finishDeletion);
                return true;
            }));
            assertThat(deletionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            var retain = executor.submit(() -> service.retain("/images/avatar/unrelated.png"));

            retain.get(5, TimeUnit.SECONDS);
            assertThat(service.referenceCount("/images/avatar/unrelated.png")).isEqualTo(1L);
            finishDeletion.countDown();
            assertThat(deletion.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void expiredClaimCanBeTakenOverWithoutMakingThePathReusable() throws Exception {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService(Duration.ZERO);
        service.replace(List.of());
        CountDownLatch firstDeletionStarted = new CountDownLatch(1);
        CountDownLatch finishFirstDeletion = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.deleteIfUnreferenced("/images/avatar/crashed.png", () -> {
                firstDeletionStarted.countDown();
                await(finishFirstDeletion);
                return true;
            }));
            assertThat(firstDeletionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(service.deleteIfUnreferenced("/images/avatar/crashed.png", () -> true))
                    .isTrue();
            assertThatThrownBy(() -> service.retain("/images/avatar/crashed.png"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("deleted");

            finishFirstDeletion.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.retain("/images/avatar/crashed.png"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rebuildDelegatesToAtomicReplaceInsteadOfClearThenIncrement() {
        AtomicReplaceOnlyReferenceService service = new AtomicReplaceOnlyReferenceService();

        service.rebuild(List.of("/images/product/item.png"));

        assertThat(service.replacement).containsExactly("/images/product/item.png");
    }

    @Test
    void deletionFailsClosedUntilTheFirstAuthoritativeSnapshotIsPublished() {
        InMemoryImageReferenceService service = new InMemoryImageReferenceService();

        assertThat(service.authoritativeSnapshotReady()).isFalse();
        assertThat(service.deleteIfUnreferenced(
                        "/images/avatar/not-initialized.png",
                        () -> {
                            throw new AssertionError("physical deletion must not run before initialization");
                        }))
                .isFalse();

        service.replace(List.of());

        assertThat(service.authoritativeSnapshotReady()).isTrue();
    }

    @Test
    void completedTombstonesAreCompactedOnlyAfterACompleteAbsentSnapshotAndRetention() {
        InMemoryImageReferenceService service =
                new InMemoryImageReferenceService(Duration.ofMinutes(30), Duration.ZERO, 1);
        service.replace(List.of());
        assertThat(service.deleteIfUnreferenced("/images/avatar/deleted.png", () -> true))
                .isTrue();
        assertThatThrownBy(() -> service.deleteIfUnreferenced("/images/avatar/next.png", () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capacity");

        ImageReferenceService.DeletionMaintenanceResult result = service.maintainDeletionState(
                List.of(), List.of(), System.currentTimeMillis() + 1L);

        assertThat(result.compactedTombstones()).isEqualTo(1);
        assertThat(service.deleteIfUnreferenced("/images/avatar/next.png", () -> true))
                .isTrue();
    }

    @Test
    void completedTombstoneIsNotCompactedWhileStorageStillContainsTheObject() {
        InMemoryImageReferenceService service =
                new InMemoryImageReferenceService(Duration.ofMinutes(30), Duration.ZERO, 1);
        service.replace(List.of());
        assertThat(service.deleteIfUnreferenced("/images/avatar/deleted.png", () -> true))
                .isTrue();

        ImageReferenceService.DeletionMaintenanceResult result = service.maintainDeletionState(
                List.of(), List.of("/images/avatar/deleted.png"), System.currentTimeMillis() + 1L);

        assertThat(result.compactedTombstones()).isZero();
        assertThatThrownBy(() -> service.deleteIfUnreferenced("/images/avatar/next.png", () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    void completedTombstoneBecomesRetryableWhenAuthoritativeListingStillContainsTheObject() {
        InMemoryImageReferenceService service =
                new InMemoryImageReferenceService(Duration.ofMinutes(30), Duration.ZERO, 1);
        service.replace(List.of());
        assertThat(service.deleteIfUnreferenced("/images/avatar/still-present.png", () -> true))
                .isTrue();

        service.maintainDeletionState(
                List.of(),
                List.of("/images/avatar/still-present.png"),
                System.currentTimeMillis() + 1L);

        assertThat(service.deleteIfUnreferenced("/images/avatar/still-present.png", () -> true))
                .isTrue();
    }

    @Test
    void productionMemoryProviderRequiresAManagedObjectToExistBeforeRetain() throws Exception {
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        when(objectStorageService.resolveObjectKey("/images/avatar/missing.png"))
                .thenReturn("avatar/missing.png");
        when(objectStorageService.exists("avatar/missing.png")).thenReturn(false);
        InMemoryImageReferenceService service = new InMemoryImageReferenceService(
                Duration.ofMinutes(30), Duration.ofDays(7), 100, objectStorageService);

        assertThatThrownBy(() -> service.retain("/images/avatar/missing.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");

        assertThat(service.referenceCount("/images/avatar/missing.png")).isZero();
    }

    private static final class AtomicReplaceOnlyReferenceService implements ImageReferenceService {

        private List<String> replacement = List.of();

        @Override
        public void retain(String imagePath) {
            throw new AssertionError("rebuild must not increment one path at a time");
        }

        @Override
        public void release(String imagePath) {}

        @Override
        public long referenceCount(String imagePath) {
            return 0L;
        }

        @Override
        public void clear() {
            throw new AssertionError("rebuild must not clear the last-known-good snapshot first");
        }

        @Override
        public void replace(java.util.Collection<String> imagePaths) {
            replacement = List.copyOf(imagePaths);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for deletion test barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for deletion test barrier", exception);
        }
    }
}

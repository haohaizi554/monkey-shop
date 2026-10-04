package com.example.monkey.shared.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class RedisImageReferenceServiceTest {

    private static final String COUNTS_HASH = "monkeyshop:image:{refs}:counts";
    private static final String TOMBSTONES_HASH = "monkeyshop:image:{refs}:tombstones";
    private static final String META_HASH = "monkeyshop:image:{refs}:meta";
    private static final String STAGING_PREFIX = "monkeyshop:image:{refs}:staging:";
    private static final String MUTATION_LOCK_KEY = "monkeyshop:image:{refs}:mutation-lock";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private RedisImageReferenceService service;

    @BeforeEach
    void setUp() {
        service = new RedisImageReferenceService(redisTemplate);
    }

    @Test
    void hotPathUsesConstantTimeHashTaggedProtocolKeysInsteadOfScanningTheLegacyHash() {
        when(redisTemplate.execute(
                        any(RedisScript.class),
                        eq(List.of(COUNTS_HASH, TOMBSTONES_HASH, META_HASH)),
                        any(Object[].class)))
                .thenReturn(1L);

        service.retain("/images/avatar/alice.png");

        ArgumentCaptor<RedisScript<Long>> script = redisScriptCaptor();
        verify(redisTemplate)
                .execute(
                        script.capture(),
                        eq(List.of(COUNTS_HASH, TOMBSTONES_HASH, META_HASH)),
                        any(Object[].class));
        assertThat(script.getValue().getScriptAsString())
                .contains("HGET", "HINCRBY")
                .doesNotContain("HKEYS", "HSCAN", "countIndex");
    }

    @Test
    void releaseCanonicalizesVariantsAndRemainsConstantTime() {
        when(redisTemplate.execute(
                        any(RedisScript.class),
                        eq(List.of(COUNTS_HASH, META_HASH)),
                        any(Object[].class)))
                .thenReturn(0L);

        service.release("/images/avatar/alice.png@320w.webp");

        ArgumentCaptor<RedisScript<Long>> script = redisScriptCaptor();
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate)
                .execute(
                        script.capture(),
                        eq(List.of(COUNTS_HASH, META_HASH)),
                        arguments.capture());
        assertThat(arguments.getValue()[0]).isEqualTo("/images/avatar/alice.png");
        assertThat(script.getValue().getScriptAsString())
                .contains("HGET", "HINCRBY", "HDEL")
                .doesNotContain("HKEYS", "HSCAN");
    }

    @Test
    void referenceCountReadsOneCanonicalFieldWithoutScanning() {
        when(redisTemplate.execute(
                        any(RedisScript.class),
                        eq(List.of(COUNTS_HASH, META_HASH)),
                        any(Object[].class)))
                .thenReturn(3L);

        assertThat(service.referenceCount("/images/avatar/alice.png@640w.webp"))
                .isEqualTo(3L);

        ArgumentCaptor<RedisScript<Long>> script = redisScriptCaptor();
        verify(redisTemplate)
                .execute(
                        script.capture(),
                        eq(List.of(COUNTS_HASH, META_HASH)),
                        any(Object[].class));
        assertThat(script.getValue().getScriptAsString())
                .contains("HGET")
                .doesNotContain("HKEYS", "HSCAN");
    }

    @Test
    void snapshotVersionLivesInTheTaggedMetadataHash() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(META_HASH, "version")).thenReturn("7");

        assertThat(service.snapshotVersion()).isEqualTo(7L);
    }

    @Test
    void clearDropsOnlyCountsAndMarksTheSnapshotUnready() {
        when(redisTemplate.execute(
                        any(RedisScript.class),
                        eq(List.of(COUNTS_HASH, META_HASH)),
                        any(Object[].class)))
                .thenReturn(4L);

        service.clear();

        ArgumentCaptor<RedisScript<Long>> script = redisScriptCaptor();
        verify(redisTemplate)
                .execute(
                        script.capture(),
                        eq(List.of(COUNTS_HASH, META_HASH)),
                        any(Object[].class));
        assertThat(script.getValue().getScriptAsString())
                .contains("DEL", "HINCRBY", "HSET")
                .doesNotContain(TOMBSTONES_HASH, "HKEYS");
    }

    @Test
    void snapshotUsesExpiringSameSlotStagingAndAtomicCasPublication() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        assertThat(service.replaceIfUnchanged(
                        List.of("/images/avatar/alice.png", "/images/avatar/alice.png"), 7L))
                .isTrue();

        ArgumentCaptor<RedisScript<Long>> scripts = redisScriptCaptor();
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<String>> keys = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate, times(2))
                .execute(scripts.capture(), keys.capture(), arguments.capture());
        assertThat(keys.getAllValues().get(0)).singleElement().asString().startsWith(STAGING_PREFIX);
        assertThat(keys.getAllValues().get(1))
                .containsExactly(COUNTS_HASH, TOMBSTONES_HASH, META_HASH, keys.getAllValues().get(0).get(0));
        assertThat(scripts.getAllValues().get(0).getScriptAsString()).contains("PEXPIRE", "HSET");
        assertThat(scripts.getAllValues().get(1).getScriptAsString())
                .contains("RENAME", "PERSIST", "HKEYS", "HEXISTS", "HGET")
                .doesNotContain("monkeyshop:image:refcount");
        assertThat(arguments.getAllValues().get(0)).contains("/images/avatar/alice.png", "2");
        assertAllProtocolKeysShareOneClusterSlot(keys.getAllValues().get(1));
    }

    @Test
    void changedVersionRejectsPublicationAndDiscardsTheStagingKey() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, 0L);

        assertThat(service.replaceIfUnchanged(List.of("/images/avatar/alice.png"), 7L))
                .isFalse();

        verify(redisTemplate).delete(org.mockito.ArgumentMatchers.startsWith(STAGING_PREFIX));
    }

    @Test
    void missingOrIncompleteStagingSnapshotFailsWithoutPublishingAnEmptySnapshot() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L, -2L);

        assertThatThrownBy(() -> service.replaceIfUnchanged(List.of("/images/avatar/alice.png"), 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staged snapshot");

        verify(redisTemplate).delete(org.mockito.ArgumentMatchers.startsWith(STAGING_PREFIX));
        verify(redisTemplate, never()).delete(COUNTS_HASH);
    }

    @Test
    void stagingFailureLeavesTheLiveCountsUntouched() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatThrownBy(() -> service.replaceIfUnchanged(List.of("/images/avatar/alice.png"), 0L))
                .isInstanceOf(IllegalStateException.class);

        verify(redisTemplate, never()).delete(COUNTS_HASH);
        verify(redisTemplate, never()).rename(any(), any());
    }

    @Test
    void deletionFailsClosedUntilTheAuthoritativeSnapshotIsReady() {
        doReturn("UNREADY")
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        @SuppressWarnings("unchecked")
        java.util.function.Supplier<Boolean> deletion = mock(java.util.function.Supplier.class);

        assertThat(service.deleteIfUnreferenced("/images/avatar/alice.png", deletion))
                .isFalse();

        verifyNoInteractions(deletion);
    }

    @Test
    void claimUsesGenerationAndPhysicalIoRunsOutsideTheGlobalMutationLock() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RedisImageReferenceService distributedService = new RedisImageReferenceService(
                redisTemplate, redissonClient, Duration.ofMinutes(30));
        doReturn("CLAIMED:9:test-token:1", 1L, 1L)
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        AtomicBoolean physicalDeletionRan = new AtomicBoolean();

        assertThat(distributedService.deleteIfUnreferenced("/images/avatar/alice.png", () -> {
                    physicalDeletionRan.set(true);
                    return true;
                }))
                .isTrue();

        assertThat(physicalDeletionRan).isTrue();
        verifyNoInteractions(redissonClient);
        ArgumentCaptor<RedisScript<Long>> scripts = redisScriptCaptor();
        verify(redisTemplate, times(3)).execute(scripts.capture(), anyList(), any(Object[].class));
        assertThat(scripts.getAllValues().get(0).getScriptAsString())
                .contains("HLEN", "HINCRBY", "CLAIMED:", "redis.call('TIME')")
                .doesNotContain("HKEYS");
        assertThat(scripts.getAllValues().get(2).getScriptAsString())
                .contains("DELETED:", "RETRYABLE:", "redis.call('TIME')");
    }

    @Test
    void failedPhysicalDeleteBecomesRetryableWithoutMakingThePathReusable() {
        doReturn("CLAIMED:1:test-token:1", 1L, 1L, -1L)
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));

        assertThat(service.deleteIfUnreferenced("/images/avatar/alice.png", () -> false))
                .isFalse();
        assertThatThrownBy(() -> service.retain("/images/avatar/alice.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deleted");
    }

    @Test
    void retainRejectsAnIncompatibleProtocolOrDeletionMarker() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-4L, -1L);

        assertThatThrownBy(() -> service.retain("/images/avatar/protocol.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protocol");
        assertThatThrownBy(() -> service.retain("/images/avatar/deleted.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deleted");
    }

    @Test
    void readinessRequiresBothTheProtocolVersionAndFirstSnapshotFlag() {
        when(redisTemplate.execute(
                        any(RedisScript.class),
                        eq(List.of(META_HASH)),
                        any(Object[].class)))
                .thenReturn(0L, 1L);

        assertThat(service.authoritativeSnapshotReady()).isFalse();
        assertThat(service.authoritativeSnapshotReady()).isTrue();
    }

    @Test
    void maintenanceUsesBoundedHashScanAndValueCasToCompactAnAbsentCompletedMarker() {
        @SuppressWarnings("unchecked")
        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.scan(eq(TOMBSTONES_HASH), any(ScanOptions.class)))
                .thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(Map.entry("/images/avatar/deleted.png", "DELETED:1"));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        RedisImageReferenceService maintenanceService = new RedisImageReferenceService(
                redisTemplate, null, Duration.ofMinutes(30), Duration.ZERO, 1L, 10);

        ImageReferenceService.DeletionMaintenanceResult result = maintenanceService.maintainDeletionState(
                List.of(), List.of(), 2L);

        assertThat(result.compactedTombstones()).isEqualTo(1);
        ArgumentCaptor<RedisScript<Long>> scripts = redisScriptCaptor();
        verify(redisTemplate, times(2)).execute(scripts.capture(), anyList(), any(Object[].class));
        assertThat(scripts.getAllValues().get(1).getScriptAsString())
                .contains("HDEL", "HGET")
                .doesNotContain("HKEYS");
        verify(cursor).close();
    }

    @Test
    void maintenanceNeverCompactsACompletedMarkerWhileTheObjectStillExists() {
        @SuppressWarnings("unchecked")
        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.scan(eq(TOMBSTONES_HASH), any(ScanOptions.class)))
                .thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(Map.entry("/images/avatar/deleted.png", "DELETED:1"));
        when(redisTemplate.execute(
                        any(RedisScript.class),
                        anyList(),
                        any(Object[].class)))
                .thenReturn(1L);
        RedisImageReferenceService maintenanceService = new RedisImageReferenceService(
                redisTemplate, null, Duration.ofMinutes(30), Duration.ZERO, 1L, 10);

        ImageReferenceService.DeletionMaintenanceResult result = maintenanceService.maintainDeletionState(
                List.of(), List.of("/images/avatar/deleted.png"), 2L);

        assertThat(result.compactedTombstones()).isZero();
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void maintenanceRequeuesACompletedMarkerWhenTheObjectStillExists() {
        @SuppressWarnings("unchecked")
        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.scan(eq(TOMBSTONES_HASH), any(ScanOptions.class)))
                .thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(Map.entry("/images/avatar/still-present.png", "DELETED:1"));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        RedisImageReferenceService maintenanceService = new RedisImageReferenceService(
                redisTemplate, null, Duration.ofMinutes(30), Duration.ZERO, 1L, 10);

        maintenanceService.maintainDeletionState(
                List.of(), List.of("/images/avatar/still-present.png"), 2L);

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate, times(2))
                .execute(any(RedisScript.class), anyList(), arguments.capture());
        assertThat(arguments.getAllValues().get(1))
                .contains("/images/avatar/still-present.png", "DELETED:1", "RETRY");
        ArgumentCaptor<RedisScript<Long>> scripts = redisScriptCaptor();
        verify(redisTemplate, times(2))
                .execute(scripts.capture(), anyList(), any(Object[].class));
        assertThat(scripts.getAllValues().get(1).getScriptAsString())
                .contains("redis.call('TIME')", "RETRYABLE:");
    }

    @Test
    void maintenanceBatchSizeIsAHardTransitionLimitInsteadOfOnlyAScanHint() {
        @SuppressWarnings("unchecked")
        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.scan(eq(TOMBSTONES_HASH), any(ScanOptions.class)))
                .thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next())
                .thenReturn(Map.entry("/images/avatar/first.png", "DELETED:1"))
                .thenReturn(Map.entry("/images/avatar/second.png", "DELETED:1"));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        RedisImageReferenceService maintenanceService = new RedisImageReferenceService(
                redisTemplate, null, Duration.ofMinutes(30), Duration.ZERO, 10L, 1);

        ImageReferenceService.DeletionMaintenanceResult result = maintenanceService.maintainDeletionState(
                List.of(), List.of(), 2L);

        assertThat(result.compactedTombstones()).isEqualTo(1);
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void explicitCompatibilityFenceStillUsesTheDistributedLock() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(MUTATION_LOCK_KEY)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedisImageReferenceService distributedService =
                new RedisImageReferenceService(redisTemplate, redissonClient);

        assertThat(distributedService.withMutationFence(() -> "done")).isEqualTo("done");

        verify(lock).lock();
        verify(lock).unlock();
    }

    @Test
    void defaultAssetsNeverMutateRedis() {
        service.retain("/images/default_avatar.png");
        service.release("/images/avatar/default_team.png");

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void productionRetainUsesTheProviderOwnedObjectKeyAndRequiresTheObjectToExist() throws Exception {
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        when(objectStorageService.resolveObjectKey("https://cdn.example/avatar/alice.png"))
                .thenReturn("avatar/alice.png");
        when(objectStorageService.exists("avatar/alice.png")).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
        RedisImageReferenceService managedService = new RedisImageReferenceService(
                redisTemplate,
                null,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                100L,
                10,
                objectStorageService);

        managedService.retain("https://cdn.example/avatar/alice.png");

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), arguments.capture());
        assertThat(arguments.getValue()[0]).isEqualTo("avatar/alice.png");
    }

    @Test
    void productionRetainFailsBeforeRedisWhenAManagedObjectIsMissing() throws Exception {
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        when(objectStorageService.resolveObjectKey("/images/avatar/missing.png"))
                .thenReturn("avatar/missing.png");
        when(objectStorageService.exists("avatar/missing.png")).thenReturn(false);
        RedisImageReferenceService managedService = new RedisImageReferenceService(
                redisTemplate,
                null,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                100L,
                10,
                objectStorageService);

        assertThatThrownBy(() -> managedService.retain("/images/avatar/missing.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void externalReferencesThatWereNotIssuedByTheProviderNeverEnterDeletionAccounting() {
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        when(objectStorageService.resolveObjectKey("https://attacker.example/product/victim.png"))
                .thenReturn(null);
        RedisImageReferenceService managedService = new RedisImageReferenceService(
                redisTemplate,
                null,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                100L,
                10,
                objectStorageService);

        managedService.retain("https://attacker.example/product/victim.png");
        assertThat(managedService.referenceCount("https://attacker.example/product/victim.png"))
                .isZero();
        assertThat(managedService.deleteIfUnreferenced(
                        "https://attacker.example/product/victim.png", () -> true))
                .isFalse();

        verifyNoInteractions(redisTemplate);
    }

    private static void assertAllProtocolKeysShareOneClusterSlot(List<String> keys) {
        assertThat(keys).allSatisfy(key -> assertThat(hashTag(key)).isEqualTo("refs"));
    }

    private static String hashTag(String key) {
        int open = key.indexOf('{');
        int close = key.indexOf('}', open + 1);
        return open >= 0 && close > open + 1 ? key.substring(open + 1, close) : key;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<RedisScript<Long>> redisScriptCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(RedisScript.class);
    }
}

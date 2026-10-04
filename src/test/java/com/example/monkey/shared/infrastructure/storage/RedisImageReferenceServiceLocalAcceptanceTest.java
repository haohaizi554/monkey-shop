package com.example.monkey.shared.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "RUN_IMAGE_REDIS_ACCEPTANCE", matches = "true")
class RedisImageReferenceServiceLocalAcceptanceTest {

    @Test
    void realLuaPreservesMultiplicityAndRejectsRetainWhileDeletionIsClaimed() throws Exception {
        try (RedisFixture fixture = RedisFixture.connect()) {
            RedisImageReferenceService first = fixture.service(Duration.ofMinutes(30), Duration.ofDays(7), 100L);
            RedisImageReferenceService second = fixture.service(Duration.ofMinutes(30), Duration.ofDays(7), 100L);
            first.replace(List.of("/images/avatar/shared.png", "/images/avatar/shared.png"));
            assertThat(first.referenceCount("/images/avatar/shared.png@320w.webp"))
                    .isEqualTo(2L);
            first.release("/images/avatar/shared.png@320w.webp");
            first.release("/images/avatar/shared.png");

            CountDownLatch deletionStarted = new CountDownLatch(1);
            CountDownLatch finishDeletion = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var deletion = executor.submit(() -> first.deleteIfUnreferenced("/images/avatar/shared.png", () -> {
                    deletionStarted.countDown();
                    await(finishDeletion);
                    return true;
                }));
                assertThat(deletionStarted.await(5, TimeUnit.SECONDS)).isTrue();

                assertThatThrownBy(() -> second.retain("/images/avatar/shared.png"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("deleted");
                second.retain("/images/avatar/unrelated.png");
                assertThat(second.referenceCount("/images/avatar/unrelated.png"))
                        .isEqualTo(1L);

                finishDeletion.countDown();
                assertThat(deletion.get(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    void realLuaMakesFailedDeletionRetryableAndCompactionRestoresCapacity() {
        try (RedisFixture fixture = RedisFixture.connect()) {
            RedisImageReferenceService service = fixture.service(Duration.ZERO, Duration.ZERO, 1L);
            service.replace(List.of());

            assertThat(service.deleteIfUnreferenced("/images/avatar/first.png", () -> false))
                    .isFalse();
            assertThat(service.deleteIfUnreferenced("/images/avatar/first.png", () -> true))
                    .isTrue();
            service.maintainDeletionState(
                    List.of(), List.of("/images/avatar/first.png"), System.currentTimeMillis() + 1L);
            assertThat(service.deleteIfUnreferenced("/images/avatar/first.png", () -> true))
                    .isTrue();
            assertThatThrownBy(() -> service.deleteIfUnreferenced("/images/avatar/second.png", () -> true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("capacity");

            var maintenance = service.maintainDeletionState(List.of(), List.of(), System.currentTimeMillis() + 1L);

            assertThat(maintenance.compactedTombstones()).isEqualTo(1);
            assertThat(service.deleteIfUnreferenced("/images/avatar/second.png", () -> true))
                    .isTrue();
        }
    }

    @Test
    void realLuaRejectsAStaleSnapshotWithoutDestroyingNewerCounts() {
        try (RedisFixture fixture = RedisFixture.connect()) {
            RedisImageReferenceService service = fixture.service(Duration.ofMinutes(30), Duration.ofDays(7), 100L);
            service.replace(List.of("/images/avatar/original.png"));
            long staleVersion = service.snapshotVersion();
            service.retain("/images/avatar/concurrent.png");

            assertThat(service.replaceIfUnchanged(List.of("/images/avatar/replacement.png"), staleVersion))
                    .isFalse();

            assertThat(service.referenceCount("/images/avatar/original.png")).isEqualTo(1L);
            assertThat(service.referenceCount("/images/avatar/concurrent.png")).isEqualTo(1L);
            assertThat(service.referenceCount("/images/avatar/replacement.png")).isZero();
        }
    }

    @Test
    void everyMultiKeyLuaKeyUsesTheSameRedisClusterHashTag() {
        List<String> keys = List.of(
                RedisImageReferenceService.COUNTS_HASH,
                RedisImageReferenceService.TOMBSTONES_HASH,
                RedisImageReferenceService.META_HASH,
                RedisImageReferenceService.STAGING_PREFIX + "snapshot");

        assertThat(keys).allSatisfy(key -> assertThat(hashTag(key)).isEqualTo("refs"));
    }

    @Test
    void publishedLiveCountsDoNotInheritTheExpiringStagingTtl() {
        try (RedisFixture fixture = RedisFixture.connect()) {
            RedisImageReferenceService service = fixture.service(Duration.ofMinutes(30), Duration.ofDays(7), 100L);

            service.replace(List.of("/images/avatar/persistent.png"));

            assertThat(fixture.redisTemplate.getExpire(RedisImageReferenceService.COUNTS_HASH, TimeUnit.MILLISECONDS))
                    .isEqualTo(-1L);
        }
    }

    @Test
    void missingOrCorruptStagingSentinelCannotReplaceTheLiveSnapshot() {
        try (RedisFixture fixture = RedisFixture.connect()) {
            RedisImageReferenceService service = fixture.service(Duration.ofMinutes(30), Duration.ofDays(7), 100L);
            service.replace(List.of("/images/avatar/original.png"));
            long version = service.snapshotVersion();

            String missingStaging = RedisImageReferenceService.STAGING_PREFIX + "missing";
            Long missingResult = publishStaging(fixture.redisTemplate, missingStaging, version);
            assertThat(missingResult).isEqualTo(-2L);
            assertThat(service.referenceCount("/images/avatar/original.png")).isEqualTo(1L);
            assertThat(service.snapshotVersion()).isEqualTo(version);

            String corruptStaging = RedisImageReferenceService.STAGING_PREFIX + "corrupt";
            fixture.redisTemplate.opsForHash().put(corruptStaging, "__snapshot_staging__", "0");
            fixture.redisTemplate.opsForHash().put(corruptStaging, "/images/avatar/replacement.png", "1");
            Long corruptResult = publishStaging(fixture.redisTemplate, corruptStaging, version);
            assertThat(corruptResult).isEqualTo(-2L);
            assertThat(service.referenceCount("/images/avatar/original.png")).isEqualTo(1L);
            assertThat(service.referenceCount("/images/avatar/replacement.png")).isZero();
            assertThat(service.snapshotVersion()).isEqualTo(version);
        }
    }

    @Test
    void realMaintenanceCapsTransitionsPerRunAndEventuallyCompactsEveryMarker() {
        try (RedisFixture fixture = RedisFixture.connect()) {
            RedisImageReferenceService service = fixture.service(Duration.ZERO, Duration.ZERO, 100L, 1);
            service.replace(List.of());
            assertThat(service.deleteIfUnreferenced("/images/avatar/first-batch.png", () -> true))
                    .isTrue();
            assertThat(service.deleteIfUnreferenced("/images/avatar/second-batch.png", () -> true))
                    .isTrue();

            var first = service.maintainDeletionState(List.of(), List.of(), Long.MAX_VALUE);
            var second = service.maintainDeletionState(List.of(), List.of(), Long.MIN_VALUE);

            assertThat(first.compactedTombstones()).isEqualTo(1);
            assertThat(second.compactedTombstones()).isEqualTo(1);
        }
    }

    private static Long publishStaging(StringRedisTemplate redisTemplate, String stagingKey, long expectedVersion) {
        return redisTemplate.execute(
                RedisImageReferenceService.PUBLISH_IF_UNCHANGED_SCRIPT,
                List.of(
                        RedisImageReferenceService.COUNTS_HASH,
                        RedisImageReferenceService.TOMBSTONES_HASH,
                        RedisImageReferenceService.META_HASH,
                        stagingKey),
                Long.toString(expectedVersion),
                "version",
                "authoritativeSnapshotReady",
                "protocolVersion",
                "2",
                "__snapshot_staging__");
    }

    private static String hashTag(String key) {
        int open = key.indexOf('{');
        int close = key.indexOf('}', open + 1);
        return open >= 0 && close > open + 1 ? key.substring(open + 1, close) : key;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for Redis deletion test barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for Redis deletion test barrier", exception);
        }
    }

    private static final class RedisFixture implements AutoCloseable {

        private final JedisConnectionFactory connectionFactory;
        private final StringRedisTemplate redisTemplate;

        private RedisFixture(JedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate) {
            this.connectionFactory = connectionFactory;
            this.redisTemplate = redisTemplate;
        }

        static RedisFixture connect() {
            String host = System.getenv().getOrDefault("IMAGE_REDIS_HOST", "127.0.0.1");
            int port = Integer.parseInt(System.getenv().getOrDefault("IMAGE_REDIS_PORT", "6397"));
            int database = Integer.parseInt(System.getenv().getOrDefault("IMAGE_REDIS_DATABASE", "15"));
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
            configuration.setDatabase(database);
            JedisConnectionFactory connectionFactory = new JedisConnectionFactory(configuration);
            connectionFactory.afterPropertiesSet();
            connectionFactory.start();
            StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
            redisTemplate.afterPropertiesSet();
            RedisFixture fixture = new RedisFixture(connectionFactory, redisTemplate);
            fixture.cleanProtocolKeys();
            return fixture;
        }

        RedisImageReferenceService service(Duration claimStaleAfter, Duration tombstoneRetention, long maxTombstones) {
            return service(claimStaleAfter, tombstoneRetention, maxTombstones, 100);
        }

        RedisImageReferenceService service(
                Duration claimStaleAfter, Duration tombstoneRetention, long maxTombstones, int maintenanceBatchSize) {
            return new RedisImageReferenceService(
                    redisTemplate, null, claimStaleAfter, tombstoneRetention, maxTombstones, maintenanceBatchSize);
        }

        @Override
        public void close() {
            cleanProtocolKeys();
            connectionFactory.destroy();
        }

        private void cleanProtocolKeys() {
            List<String> keys = new ArrayList<>(List.of(
                    RedisImageReferenceService.COUNTS_HASH,
                    RedisImageReferenceService.TOMBSTONES_HASH,
                    RedisImageReferenceService.META_HASH));
            Set<String> staging = redisTemplate.keys(RedisImageReferenceService.STAGING_PREFIX + "*");
            if (staging != null) {
                keys.addAll(staging);
            }
            redisTemplate.delete(keys);
        }
    }
}

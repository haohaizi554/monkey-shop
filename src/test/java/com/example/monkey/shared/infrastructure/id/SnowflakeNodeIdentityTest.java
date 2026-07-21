package com.example.monkey.shared.infrastructure.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.order.infrastructure.SnowflakeOrderNumberGenerator;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class SnowflakeNodeIdentityTest {

    @Test
    void twoReplicaLeasesProduceDistinctIdsAtTheSameTimestamp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(0L, 1L);

        SnowflakeNodeIdentity first = SnowflakeNodeIdentity.distributed(
                redisTemplate, "replica-a", "monkeyshop/prod", Duration.ofSeconds(30), System::nanoTime);
        SnowflakeNodeIdentity second = SnowflakeNodeIdentity.distributed(
                redisTemplate, "replica-b", "monkeyshop/prod", Duration.ofSeconds(30), System::nanoTime);
        AtomicLong now = new AtomicLong(SnowflakeIdGenerator.CUSTOM_EPOCH_MILLIS + 1_000L);

        try {
            assertThat(first.nodeId()).isNotEqualTo(second.nodeId());
            assertThat(new SnowflakeIdGenerator(first, now::get).nextId())
                    .isNotEqualTo(new SnowflakeIdGenerator(second, now::get).nextId());
        } finally {
            second.destroy();
            first.destroy();
        }
    }

    @Test
    void lostLeaseFencesIdGeneration() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(17L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(0L);
        SnowflakeNodeIdentity identity = SnowflakeNodeIdentity.distributed(
                redisTemplate, "replica-a", "monkeyshop/prod", Duration.ofSeconds(30), System::nanoTime);

        identity.renewLease();

        assertThatThrownBy(identity::assertLeaseValid)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to generate");
    }

    @Test
    void idGenerationIsFencedWhenLeaseExpiresDuringAssembly() {
        SnowflakeNodeIdentity identity = expiringIdentity();
        AtomicLong now = new AtomicLong(SnowflakeIdGenerator.CUSTOM_EPOCH_MILLIS + 1_000L);

        try {
            assertThatThrownBy(() -> new SnowflakeIdGenerator(identity, now::get).nextId())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refusing to generate");
        } finally {
            identity.destroy();
        }
    }

    @Test
    void orderNumberGenerationIsFencedWhenLeaseExpiresDuringAssembly() {
        SnowflakeNodeIdentity identity = expiringIdentity();

        try {
            assertThatThrownBy(() -> new SnowflakeOrderNumberGenerator(identity).nextOrderNo())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refusing to generate");
        } finally {
            identity.destroy();
        }
    }

    @Test
    void localIdentityCombinesMaximumWorkerAndDatacenterComponents() {
        SnowflakeNodeIdentity identity = localIdentity(31, 31);

        assertThat(identity.workerId()).isEqualTo(31);
        assertThat(identity.datacenterId()).isEqualTo(31);
        assertThat(identity.nodeId()).isEqualTo(1_023);
        identity.assertLeaseValid();
        identity.renewLease();
        identity.destroy();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 32})
    void localIdentityRejectsOutOfRangeWorkerIds(long workerId) {
        assertThatThrownBy(() -> localIdentity(workerId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("worker id must be between 0 and 31");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 32})
    void localIdentityRejectsOutOfRangeDatacenterIds(long datacenterId) {
        assertThatThrownBy(() -> localIdentity(0, datacenterId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("datacenter id must be between 0 and 31");
    }

    private static SnowflakeNodeIdentity expiringIdentity() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(0L);
        AtomicInteger nanoReads = new AtomicInteger();
        return SnowflakeNodeIdentity.distributed(
                redisTemplate,
                "replica-a",
                "monkeyshop/prod",
                Duration.ofSeconds(30),
                () -> nanoReads.getAndIncrement() < 2
                        ? 0L
                        : Duration.ofSeconds(30).toNanos());
    }

    @SuppressWarnings("unchecked")
    private static SnowflakeNodeIdentity localIdentity(long workerId, long datacenterId) {
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        return new SnowflakeNodeIdentity(
                redisProvider,
                false,
                workerId,
                datacenterId,
                "",
                "monkeyshop/test",
                Duration.ofSeconds(30),
                Duration.ofSeconds(10));
    }
}

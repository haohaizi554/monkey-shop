package com.example.monkey.shared.infrastructure.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SnowflakeIdGeneratorTest {

    @Test
    void generatesMonotonicIdsWithSharedBitLayout() {
        AtomicLong now = new AtomicLong(SnowflakeIdGenerator.CUSTOM_EPOCH_MILLIS + 1_000L);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 2L, now::get);

        long first = generator.nextId();
        long second = generator.nextId();
        now.incrementAndGet();
        long third = generator.nextId();

        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    void rejectsClockRollback() {
        AtomicLong now = new AtomicLong(SnowflakeIdGenerator.CUSTOM_EPOCH_MILLIS + 1_000L);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(0L, 0L, now::get);

        generator.nextId();
        now.decrementAndGet();

        assertThatThrownBy(generator::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Clock moved backwards");
    }
}

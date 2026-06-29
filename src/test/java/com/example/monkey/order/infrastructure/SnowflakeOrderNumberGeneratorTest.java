package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SnowflakeOrderNumberGeneratorTest {

    @Test
    void generatesMonotonicUniqueOrderNumbersWithinSameMillisecond() {
        AtomicLong now = new AtomicLong(SnowflakeOrderNumberGenerator.CUSTOM_EPOCH_MILLIS + 1_000L);
        SnowflakeOrderNumberGenerator generator = new SnowflakeOrderNumberGenerator(3L, 2L, now::get);

        Set<String> orderNumbers = new HashSet<>();
        String previous = generator.nextOrderNo();
        orderNumbers.add(previous);

        for (int i = 0; i < 100; i++) {
            String current = generator.nextOrderNo();
            assertThat(numericId(current)).isGreaterThan(numericId(previous));
            assertThat(orderNumbers).doesNotContain(current);
            orderNumbers.add(current);
            previous = current;
        }
    }

    @Test
    void advancesAcrossMilliseconds() {
        AtomicLong now = new AtomicLong(SnowflakeOrderNumberGenerator.CUSTOM_EPOCH_MILLIS + 1_000L);
        SnowflakeOrderNumberGenerator generator = new SnowflakeOrderNumberGenerator(0L, 0L, now::get);

        String first = generator.nextOrderNo();
        now.incrementAndGet();
        String second = generator.nextOrderNo();

        assertThat(numericId(second)).isGreaterThan(numericId(first));
    }

    @Test
    void rejectsOutOfRangeNodeIdentifiers() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new SnowflakeOrderNumberGenerator(32L, 0L, System::currentTimeMillis))
                .withMessageContaining("worker id");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new SnowflakeOrderNumberGenerator(0L, 32L, System::currentTimeMillis))
                .withMessageContaining("datacenter id");
    }

    @Test
    void rejectsClockRollback() {
        AtomicLong now = new AtomicLong(SnowflakeOrderNumberGenerator.CUSTOM_EPOCH_MILLIS + 1_000L);
        SnowflakeOrderNumberGenerator generator = new SnowflakeOrderNumberGenerator(0L, 0L, now::get);

        generator.nextOrderNo();
        now.decrementAndGet();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(generator::nextOrderNo)
                .withMessageContaining("Clock moved backwards");
    }

    private static long numericId(String orderNo) {
        assertThat(orderNo).matches("ORD\\d+");
        return Long.parseLong(orderNo.substring(3));
    }
}

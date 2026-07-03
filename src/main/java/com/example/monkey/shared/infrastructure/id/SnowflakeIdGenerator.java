package com.example.monkey.shared.infrastructure.id;

import com.example.monkey.shared.domain.id.IdGenerator;
import java.time.Instant;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdGenerator implements IdGenerator {

    public static final long CUSTOM_EPOCH_MILLIS =
            Instant.parse("2024-01-01T00:00:00Z").toEpochMilli();

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private final long workerId;
    private final long datacenterId;
    private final LongSupplier currentTimeMillis;

    private long sequence;
    private long lastTimestamp = -1L;

    @Autowired
    public SnowflakeIdGenerator(
            @Value("${app.snowflake.worker-id:${app.order.snowflake.worker-id:0}}") long workerId,
            @Value("${app.snowflake.datacenter-id:${app.order.snowflake.datacenter-id:0}}") long datacenterId) {
        this(workerId, datacenterId, System::currentTimeMillis);
    }

    public SnowflakeIdGenerator(long workerId, long datacenterId, LongSupplier currentTimeMillis) {
        validateRange("worker id", workerId, MAX_WORKER_ID);
        validateRange("datacenter id", datacenterId, MAX_DATACENTER_ID);
        this.workerId = workerId;
        this.datacenterId = datacenterId;
        this.currentTimeMillis = currentTimeMillis;
    }

    @Override
    public synchronized long nextId() {
        long timestamp = currentTimeMillis.getAsLong();
        if (timestamp < CUSTOM_EPOCH_MILLIS) {
            throw new IllegalStateException("Snowflake clock is before the custom epoch");
        }
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("Clock moved backwards while generating snowflake id");
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;
        return ((timestamp - CUSTOM_EPOCH_MILLIS) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitUntilNextMillis(long previousTimestamp) {
        long timestamp = currentTimeMillis.getAsLong();
        while (timestamp <= previousTimestamp) {
            timestamp = currentTimeMillis.getAsLong();
        }
        return timestamp;
    }

    private static void validateRange(String field, long value, long maxValue) {
        if (value < 0 || value > maxValue) {
            throw new IllegalArgumentException(field + " must be between 0 and " + maxValue);
        }
    }
}

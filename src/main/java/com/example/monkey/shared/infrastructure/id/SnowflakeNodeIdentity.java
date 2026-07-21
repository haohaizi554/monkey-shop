package com.example.monkey.shared.infrastructure.id;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SnowflakeNodeIdentity {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeNodeIdentity.class);
    private static final int NODE_ID_BITS = 10;
    private static final int WORKER_ID_BITS = 5;
    private static final int MAX_NODE_ID = (1 << NODE_ID_BITS) - 1;
    private static final int MAX_COMPONENT_ID = (1 << WORKER_ID_BITS) - 1;
    private static final Duration MINIMUM_LEASE_DURATION = Duration.ofSeconds(10);
    private static final int MAX_OWNER_LENGTH = 256;

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = (time[1] * 1000) + math.floor(time[2] / 1000)
            local expires = now + tonumber(ARGV[2])
            for slot = 0, 1023 do
              local ownerField = 'slot:' .. slot .. ':owner'
              local expiryField = 'slot:' .. slot .. ':expires'
              local owner = redis.call('HGET', KEYS[1], ownerField)
              local expiry = tonumber(redis.call('HGET', KEYS[1], expiryField) or '0')
              if (not owner) or expiry <= now then
                redis.call('HSET', KEYS[1],
                  ownerField, ARGV[1],
                  expiryField, expires)
                redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]) * 2)
                return slot
              end
            end
            return -1
            """, Long.class);

    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            local ownerField = 'slot:' .. ARGV[2] .. ':owner'
            local expiryField = 'slot:' .. ARGV[2] .. ':expires'
            if redis.call('HGET', KEYS[1], ownerField) ~= ARGV[1] then
              return 0
            end
            local time = redis.call('TIME')
            local now = (time[1] * 1000) + math.floor(time[2] / 1000)
            redis.call('HSET', KEYS[1], expiryField, now + tonumber(ARGV[3]))
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]) * 2)
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local ownerField = 'slot:' .. ARGV[2] .. ':owner'
            local expiryField = 'slot:' .. ARGV[2] .. ':expires'
            if redis.call('HGET', KEYS[1], ownerField) ~= ARGV[1] then
              return 0
            end
            redis.call('HDEL', KEYS[1], ownerField, expiryField)
            return 1
            """, Long.class);

    private final boolean distributed;
    private final StringRedisTemplate redisTemplate;
    private final String registryKey;
    private final String leaseOwner;
    private final Duration leaseDuration;
    private final LongSupplier nanoTime;
    private final long workerId;
    private final long datacenterId;
    private final int nodeId;

    private volatile long leaseValidUntilNanos = Long.MAX_VALUE;
    private volatile boolean closed;

    @Autowired
    public SnowflakeNodeIdentity(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.snowflake.node-lease.enabled:false}") boolean distributed,
            @Value("${app.snowflake.worker-id:${app.order.snowflake.worker-id:0}}") long workerId,
            @Value("${app.snowflake.datacenter-id:${app.order.snowflake.datacenter-id:0}}") long datacenterId,
            @Value("${app.snowflake.instance-id:}") String instanceId,
            @Value("${app.snowflake.node-lease.namespace:${spring.application.name:monkeyshop}}") String namespace,
            @Value("${app.snowflake.node-lease.duration:PT30S}") Duration leaseDuration,
            @Value("${app.snowflake.node-lease.renew-interval:PT10S}") Duration renewInterval) {
        this(
                distributed,
                redisTemplateProvider.getIfAvailable(),
                workerId,
                datacenterId,
                instanceId,
                namespace,
                leaseDuration,
                renewInterval,
                System::nanoTime);
    }

    private SnowflakeNodeIdentity(
            boolean distributed,
            StringRedisTemplate redisTemplate,
            long workerId,
            long datacenterId,
            String instanceId,
            String namespace,
            Duration leaseDuration,
            Duration renewInterval,
            LongSupplier nanoTime) {
        this.distributed = distributed;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (!distributed) {
            validateComponentId("worker id", workerId);
            validateComponentId("datacenter id", datacenterId);
            this.redisTemplate = null;
            this.registryKey = null;
            this.leaseOwner = null;
            this.leaseDuration = Duration.ZERO;
            this.workerId = workerId;
            this.datacenterId = datacenterId;
            this.nodeId = Math.toIntExact((datacenterId << WORKER_ID_BITS) | workerId);
            return;
        }

        this.redisTemplate =
                Objects.requireNonNull(redisTemplate, "Redis is required for distributed Snowflake node leases");
        String normalizedInstanceId = requireText("Snowflake instance id", instanceId);
        if (normalizedInstanceId.length() > MAX_OWNER_LENGTH) {
            throw new IllegalArgumentException(
                    "Snowflake instance id must not exceed " + MAX_OWNER_LENGTH + " characters");
        }
        String normalizedNamespace = requireText("Snowflake node lease namespace", namespace);
        validateLeaseTiming(leaseDuration, renewInterval);
        this.registryKey = "snowflake:nodes:{" + sha256(normalizedNamespace) + "}";
        this.leaseOwner = normalizedInstanceId + ":" + UUID.randomUUID();
        this.leaseDuration = leaseDuration;
        this.nodeId = acquireNodeId();
        this.workerId = nodeId & MAX_COMPONENT_ID;
        this.datacenterId = (nodeId >> WORKER_ID_BITS) & MAX_COMPONENT_ID;
        log.info(
                "Acquired distributed Snowflake node identity {}/{} for instance {}",
                this.datacenterId,
                this.workerId,
                normalizedInstanceId);
    }

    static SnowflakeNodeIdentity distributed(
            StringRedisTemplate redisTemplate,
            String instanceId,
            String namespace,
            Duration leaseDuration,
            LongSupplier nanoTime) {
        return new SnowflakeNodeIdentity(
                true, redisTemplate, 0, 0, instanceId, namespace, leaseDuration, leaseDuration.dividedBy(3), nanoTime);
    }

    public long workerId() {
        return workerId;
    }

    public long datacenterId() {
        return datacenterId;
    }

    public int nodeId() {
        return nodeId;
    }

    public void assertLeaseValid() {
        if (distributed && (closed || nanoTime.getAsLong() >= leaseValidUntilNanos)) {
            throw new IllegalStateException(
                    "Distributed Snowflake node lease is no longer valid; refusing to generate an id");
        }
    }

    @Scheduled(
            fixedDelayString = "${app.snowflake.node-lease.renew-interval:PT10S}",
            scheduler = SnowflakeLeaseSchedulingConfiguration.SCHEDULER_BEAN_NAME)
    void renewLease() {
        if (!distributed || closed) {
            return;
        }
        long startedAtNanos = nanoTime.getAsLong();
        try {
            Long renewed = redisTemplate.execute(
                    RENEW_SCRIPT,
                    List.of(registryKey),
                    leaseOwner,
                    Integer.toString(nodeId),
                    Long.toString(leaseDuration.toMillis()));
            if (!Long.valueOf(1L).equals(renewed)) {
                leaseValidUntilNanos = Long.MIN_VALUE;
                log.error("Lost distributed Snowflake node identity {}", nodeId);
                return;
            }
            if (closed) {
                return;
            }
            markLeaseValid(startedAtNanos);
        } catch (RuntimeException exception) {
            log.warn(
                    "Could not renew distributed Snowflake node identity {}; generation will stop before lease expiry",
                    nodeId,
                    exception);
        }
    }

    @PreDestroy
    public void destroy() {
        if (!distributed || closed) {
            return;
        }
        closed = true;
        leaseValidUntilNanos = Long.MIN_VALUE;
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(registryKey), leaseOwner, Integer.toString(nodeId));
        } catch (RuntimeException exception) {
            log.warn("Could not release distributed Snowflake node identity {}", nodeId, exception);
        }
    }

    private int acquireNodeId() {
        long startedAtNanos = nanoTime.getAsLong();
        Long acquired = redisTemplate.execute(
                ACQUIRE_SCRIPT, List.of(registryKey), leaseOwner, Long.toString(leaseDuration.toMillis()));
        if (acquired == null || acquired < 0 || acquired > MAX_NODE_ID) {
            throw new IllegalStateException("No distributed Snowflake node identity is available");
        }
        markLeaseValid(startedAtNanos);
        return acquired.intValue();
    }

    private void markLeaseValid(long startedAtNanos) {
        long safetyMarginNanos = leaseDuration.toNanos() / 4;
        leaseValidUntilNanos = startedAtNanos + leaseDuration.toNanos() - safetyMarginNanos;
    }

    private static void validateComponentId(String field, long value) {
        if (value < 0 || value > MAX_COMPONENT_ID) {
            throw new IllegalArgumentException(field + " must be between 0 and " + MAX_COMPONENT_ID);
        }
    }

    private static void validateLeaseTiming(Duration leaseDuration, Duration renewInterval) {
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(renewInterval, "renewInterval");
        if (leaseDuration.compareTo(MINIMUM_LEASE_DURATION) < 0) {
            throw new IllegalArgumentException(
                    "Snowflake node lease duration must be at least " + MINIMUM_LEASE_DURATION);
        }
        if (renewInterval.isZero()
                || renewInterval.isNegative()
                || renewInterval.multipliedBy(2).compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "Snowflake node lease renew interval must be less than half the lease duration");
        }
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

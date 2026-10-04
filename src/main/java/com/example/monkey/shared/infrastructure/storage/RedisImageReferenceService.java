package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.image-reference.provider", havingValue = "redis")
public class RedisImageReferenceService implements ImageReferenceService {

    private static final Logger log = LoggerFactory.getLogger(RedisImageReferenceService.class);

    /* Every protocol key shares one Redis Cluster slot. The pre-v2 hash is rebuilt from the first
     * complete DB/storage snapshot after old writer pods have drained; it is never read by a hot path. */
    static final String COUNTS_HASH = "monkeyshop:image:{refs}:counts";
    static final String TOMBSTONES_HASH = "monkeyshop:image:{refs}:tombstones";
    static final String META_HASH = "monkeyshop:image:{refs}:meta";
    static final String STAGING_PREFIX = "monkeyshop:image:{refs}:staging:";
    static final String MUTATION_LOCK_KEY = "monkeyshop:image:{refs}:mutation-lock";

    private static final String VERSION_FIELD = "version";
    private static final String READY_FIELD = "authoritativeSnapshotReady";
    private static final String PROTOCOL_FIELD = "protocolVersion";
    private static final String CLAIM_GENERATION_FIELD = "deletionClaimGeneration";
    private static final String PROTOCOL_VERSION = "2";
    private static final String STAGING_SENTINEL = "__snapshot_staging__";
    private static final long DEFAULT_MAX_DELETION_TOMBSTONES = 100_000L;
    private static final int DEFAULT_MAINTENANCE_BATCH_SIZE = 500;
    private static final Duration STAGING_TTL = Duration.ofMinutes(10);

    private static final DefaultRedisScript<Long> RETAIN_SCRIPT = new DefaultRedisScript<>("""
            local protocol = redis.call('HGET', KEYS[3], ARGV[3])
            if protocol and protocol ~= ARGV[4] then
              return -4
            end
            if redis.call('HEXISTS', KEYS[2], ARGV[1]) == 1 then
              return -1
            end
            redis.call('HSET', KEYS[3], ARGV[3], ARGV[4])
            local count = redis.call('HINCRBY', KEYS[1], ARGV[1], 1)
            redis.call('HINCRBY', KEYS[3], ARGV[2], 1)
            return count
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local protocol = redis.call('HGET', KEYS[2], ARGV[3])
            if protocol and protocol ~= ARGV[4] then
              return -4
            end
            redis.call('HSET', KEYS[2], ARGV[3], ARGV[4])
            local existing = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
            if existing <= 0 then
              return 0
            end
            local count = redis.call('HINCRBY', KEYS[1], ARGV[1], -1)
            if count <= 0 then
              redis.call('HDEL', KEYS[1], ARGV[1])
              count = 0
            end
            redis.call('HINCRBY', KEYS[2], ARGV[2], 1)
            return count
            """, Long.class);

    private static final DefaultRedisScript<Long> REFERENCE_COUNT_SCRIPT = new DefaultRedisScript<>("""
            local protocol = redis.call('HGET', KEYS[2], ARGV[2])
            if protocol and protocol ~= ARGV[3] then
              return -4
            end
            return tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
            """, Long.class);

    private static final DefaultRedisScript<Long> READY_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], ARGV[2]) ~= ARGV[3] then
              return 0
            end
            if redis.call('HGET', KEYS[1], ARGV[1]) ~= '1' then
              return 0
            end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> CLEAR_SCRIPT = new DefaultRedisScript<>("""
            local protocol = redis.call('HGET', KEYS[2], ARGV[3])
            if protocol and protocol ~= ARGV[4] then
              return -4
            end
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[2], ARGV[2], '0')
            redis.call('HSET', KEYS[2], ARGV[3], ARGV[4])
            return redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
            """, Long.class);

    private static final DefaultRedisScript<Long> STAGE_SNAPSHOT_SCRIPT = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], ARGV[2], '1')
            for index = 3, #ARGV, 2 do
              redis.call('HSET', KEYS[1], ARGV[index], ARGV[index + 1])
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    static final DefaultRedisScript<Long> PUBLISH_IF_UNCHANGED_SCRIPT = new DefaultRedisScript<>("""
                    local protocol = redis.call('HGET', KEYS[3], ARGV[4])
                    if protocol and protocol ~= ARGV[5] then
                      return -4
                    end
                    local current = tonumber(redis.call('HGET', KEYS[3], ARGV[2]) or '0')
                    if current ~= tonumber(ARGV[1]) then
                      return 0
                    end
                    if redis.call('HGET', KEYS[4], ARGV[6]) ~= '1' then
                      return -2
                    end
                    local fields = redis.call('HKEYS', KEYS[4])
                    for _, field in ipairs(fields) do
                      if field ~= ARGV[6] and redis.call('HEXISTS', KEYS[2], field) == 1 then
                        return -1
                      end
                    end
                    redis.call('HDEL', KEYS[4], ARGV[6])
                    redis.call('DEL', KEYS[1])
                    if redis.call('HLEN', KEYS[4]) > 0 then
                      redis.call('PERSIST', KEYS[4])
                      redis.call('RENAME', KEYS[4], KEYS[1])
                    else
                      redis.call('DEL', KEYS[4])
                    end
                    local nextVersion = current + 1
                    redis.call('HSET', KEYS[3], ARGV[2], nextVersion)
                    redis.call('HSET', KEYS[3], ARGV[3], '1')
                    redis.call('HSET', KEYS[3], ARGV[4], ARGV[5])
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<String> CLAIM_DELETION_SCRIPT = new DefaultRedisScript<>("""
            local protocol = redis.call('HGET', KEYS[3], ARGV[5])
            if protocol and protocol ~= ARGV[6] then
              return 'PROTOCOL'
            end
            if protocol ~= ARGV[6] or redis.call('HGET', KEYS[3], ARGV[4]) ~= '1' then
              return 'UNREADY'
            end
            if tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0') > 0 then
              return 'BUSY'
            end
            local redisTime = redis.call('TIME')
            local claimedAt = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)
            local marker = redis.call('HGET', KEYS[2], ARGV[1])
            if marker then
              if string.sub(marker, 1, 10) == 'RETRYABLE:' then
                -- retryable claims remain unavailable to retain while a new owner takes over
              elseif string.sub(marker, 1, 8) == 'CLAIMED:' then
                local previousClaimedAt = tonumber(string.match(marker, ':(%d+)$') or '0')
                if previousClaimedAt <= 0 or claimedAt - previousClaimedAt < tonumber(ARGV[3]) then
                  return 'BUSY'
                end
              else
                return 'BUSY'
              end
            elseif redis.call('HLEN', KEYS[2]) >= tonumber(ARGV[7]) then
              return 'CAPACITY'
            end
            local generation = redis.call('HINCRBY', KEYS[3], ARGV[8], 1)
            local claim = 'CLAIMED:' .. generation .. ':' .. ARGV[2] .. ':' .. claimedAt
            redis.call('HSET', KEYS[2], ARGV[1], claim)
            return claim
            """, String.class);

    private static final DefaultRedisScript<Long> VERIFY_DELETION_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[3], ARGV[3]) ~= ARGV[4]
                or redis.call('HGET', KEYS[3], ARGV[2]) ~= '1' then
              return 0
            end
            if redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[5] then
              return 0
            end
            if tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0') ~= 0 then
              return 0
            end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> FINALIZE_DELETION_SCRIPT = new DefaultRedisScript<>("""
                    if redis.call('HGET', KEYS[1], ARGV[1]) ~= ARGV[2] then
                      return 0
                    end
                    local redisTime = redis.call('TIME')
                    local completedAt = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)
                    if ARGV[3] == '1' then
                      redis.call('HSET', KEYS[1], ARGV[1], 'DELETED:' .. completedAt)
                    else
                      redis.call('HSET', KEYS[1], ARGV[1], 'RETRYABLE:' .. completedAt)
                    end
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<Long> ABANDON_DELETION_SCRIPT = new DefaultRedisScript<>("""
                    if redis.call('HGET', KEYS[1], ARGV[1]) ~= ARGV[2] then
                      return 0
                    end
                    return redis.call('HDEL', KEYS[1], ARGV[1])
                    """, Long.class);

    private static final DefaultRedisScript<Long> MAINTAIN_TOMBSTONE_SCRIPT = new DefaultRedisScript<>("""
                    if redis.call('HGET', KEYS[3], ARGV[4]) ~= ARGV[5]
                        or redis.call('HGET', KEYS[3], ARGV[3]) ~= '1' then
                      return -3
                    end
                    if redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[2] then
                      return 0
                    end
                    if tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0') ~= 0 then
                      return 0
                    end
                    local redisTime = redis.call('TIME')
                    local now = redisTime[1] * 1000 + math.floor(redisTime[2] / 1000)
                    local changedAt = tonumber(string.match(ARGV[2], ':(%d+)$') or '0')
                    if ARGV[6] == 'RETRY' and string.sub(ARGV[2], 1, 8) == 'DELETED:' then
                      redis.call('HSET', KEYS[2], ARGV[1], 'RETRYABLE:' .. now)
                      return 1
                    end
                    if ARGV[6] == 'RECOVER' and string.sub(ARGV[2], 1, 8) == 'CLAIMED:'
                        and changedAt > 0 and now - changedAt >= tonumber(ARGV[7]) then
                      redis.call('HSET', KEYS[2], ARGV[1], 'DELETED:' .. now)
                      return 1
                    end
                    if ARGV[6] == 'MARK' and string.sub(ARGV[2], 1, 10) == 'RETRYABLE:' then
                      redis.call('HSET', KEYS[2], ARGV[1], 'DELETED:' .. now)
                      return 1
                    end
                    if ARGV[6] == 'COMPACT' and string.sub(ARGV[2], 1, 8) == 'DELETED:'
                        and changedAt > 0 and now - changedAt >= tonumber(ARGV[8]) then
                      return redis.call('HDEL', KEYS[2], ARGV[1])
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final Duration deletionClaimStaleAfter;
    private final Duration deletionTombstoneRetention;
    private final long maxDeletionTombstones;
    private final int maintenanceBatchSize;
    private final ObjectStorageService objectStorageService;

    public RedisImageReferenceService(StringRedisTemplate redisTemplate) {
        this(
                redisTemplate,
                null,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                DEFAULT_MAX_DELETION_TOMBSTONES,
                DEFAULT_MAINTENANCE_BATCH_SIZE);
    }

    public RedisImageReferenceService(StringRedisTemplate redisTemplate, RedissonClient redissonClient) {
        this(
                redisTemplate,
                redissonClient,
                Duration.ofMinutes(30),
                Duration.ofDays(7),
                DEFAULT_MAX_DELETION_TOMBSTONES,
                DEFAULT_MAINTENANCE_BATCH_SIZE);
    }

    public RedisImageReferenceService(
            StringRedisTemplate redisTemplate, RedissonClient redissonClient, Duration deletionClaimStaleAfter) {
        this(
                redisTemplate,
                redissonClient,
                deletionClaimStaleAfter,
                Duration.ofDays(7),
                DEFAULT_MAX_DELETION_TOMBSTONES,
                DEFAULT_MAINTENANCE_BATCH_SIZE);
    }

    public RedisImageReferenceService(
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient,
            @Value("${app.upload.cleanup.deletion-claim-stale-after:PT30M}") Duration deletionClaimStaleAfter,
            @Value("${app.upload.cleanup.deletion-tombstone-retention:P7D}") Duration deletionTombstoneRetention,
            @Value("${app.upload.cleanup.max-deletion-tombstones:100000}") long maxDeletionTombstones,
            @Value("${app.upload.cleanup.deletion-maintenance-batch-size:500}") int maintenanceBatchSize) {
        this(
                redisTemplate,
                redissonClient,
                deletionClaimStaleAfter,
                deletionTombstoneRetention,
                maxDeletionTombstones,
                maintenanceBatchSize,
                null);
    }

    @Autowired
    public RedisImageReferenceService(
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient,
            @Value("${app.upload.cleanup.deletion-claim-stale-after:PT30M}") Duration deletionClaimStaleAfter,
            @Value("${app.upload.cleanup.deletion-tombstone-retention:P7D}") Duration deletionTombstoneRetention,
            @Value("${app.upload.cleanup.max-deletion-tombstones:100000}") long maxDeletionTombstones,
            @Value("${app.upload.cleanup.deletion-maintenance-batch-size:500}") int maintenanceBatchSize,
            ObjectStorageService objectStorageService) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.deletionClaimStaleAfter = requireNonNegative(deletionClaimStaleAfter, "deletionClaimStaleAfter");
        this.deletionTombstoneRetention = requireNonNegative(deletionTombstoneRetention, "deletionTombstoneRetention");
        if (maxDeletionTombstones <= 0L) {
            throw new IllegalArgumentException("maxDeletionTombstones must be positive");
        }
        if (maintenanceBatchSize <= 0) {
            throw new IllegalArgumentException("maintenanceBatchSize must be positive");
        }
        this.maxDeletionTombstones = maxDeletionTombstones;
        this.maintenanceBatchSize = maintenanceBatchSize;
        this.objectStorageService = objectStorageService;
    }

    @Override
    public void retain(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        String path = managedPath(imagePath, true);
        if (path == null) {
            return;
        }
        Long count = redisTemplate.execute(
                RETAIN_SCRIPT,
                List.of(COUNTS_HASH, TOMBSTONES_HASH, META_HASH),
                path,
                VERSION_FIELD,
                PROTOCOL_FIELD,
                PROTOCOL_VERSION);
        if (count != null && count == -1L) {
            throw new IllegalStateException("Image path has already been deleted and cannot be reused");
        }
        assertCompatibleAndSuccessful(count, "retain image reference");
    }

    @Override
    public void release(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        String path = managedPath(imagePath, false);
        if (path == null) {
            return;
        }
        Long count = redisTemplate.execute(
                RELEASE_SCRIPT, List.of(COUNTS_HASH, META_HASH), path, VERSION_FIELD, PROTOCOL_FIELD, PROTOCOL_VERSION);
        if (count == null || count == -4L || count < 0L) {
            throw protocolFailure("release image reference", count);
        }
    }

    @Override
    public long referenceCount(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return 0L;
        }
        String path = managedPath(imagePath, false);
        if (path == null) {
            return 0L;
        }
        Long count = redisTemplate.execute(
                REFERENCE_COUNT_SCRIPT, List.of(COUNTS_HASH, META_HASH), path, PROTOCOL_FIELD, PROTOCOL_VERSION);
        if (count == null || count == -4L || count < 0L) {
            throw protocolFailure("read image reference count", count);
        }
        return count;
    }

    @Override
    public void clear() {
        Long version = redisTemplate.execute(
                CLEAR_SCRIPT,
                List.of(COUNTS_HASH, META_HASH),
                VERSION_FIELD,
                READY_FIELD,
                PROTOCOL_FIELD,
                PROTOCOL_VERSION);
        if (version == null || version == -4L || version <= 0L) {
            throw protocolFailure("clear image references", version);
        }
    }

    @Override
    public void replace(Collection<String> imagePaths) {
        long expectedVersion = snapshotVersion();
        if (!publishIfUnchanged(imagePaths, expectedVersion)) {
            throw new IllegalStateException("Image references changed while publishing a replacement snapshot");
        }
    }

    @Override
    public long snapshotVersion() {
        return parseNonNegative(redisTemplate.opsForHash().get(META_HASH, VERSION_FIELD));
    }

    @Override
    public boolean replaceIfUnchanged(Collection<String> imagePaths, long expectedVersion) {
        return publishIfUnchanged(imagePaths, expectedVersion);
    }

    @Override
    public boolean authoritativeSnapshotReady() {
        Long ready =
                redisTemplate.execute(READY_SCRIPT, List.of(META_HASH), READY_FIELD, PROTOCOL_FIELD, PROTOCOL_VERSION);
        if (ready == null || ready < 0L) {
            throw new IllegalStateException("Unable to read image reference snapshot readiness from Redis");
        }
        return ready == 1L;
    }

    @Override
    public boolean deleteIfUnreferenced(String imagePath, Supplier<Boolean> physicalDeletion) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return false;
        }
        String path = managedPath(imagePath, false);
        if (path == null) {
            return false;
        }
        String token = UUID.randomUUID().toString();
        String claim = redisTemplate.execute(
                CLAIM_DELETION_SCRIPT,
                List.of(COUNTS_HASH, TOMBSTONES_HASH, META_HASH),
                path,
                token,
                Long.toString(deletionClaimStaleAfter.toMillis()),
                READY_FIELD,
                PROTOCOL_FIELD,
                PROTOCOL_VERSION,
                Long.toString(maxDeletionTombstones),
                CLAIM_GENERATION_FIELD);
        if ("UNREADY".equals(claim)) {
            log.warn("Skipped image deletion until a compatible authoritative reference snapshot is ready");
            return false;
        }
        if ("CAPACITY".equals(claim)) {
            throw new IllegalStateException("Image deletion tombstone capacity is exhausted; cleanup is disabled");
        }
        if ("PROTOCOL".equals(claim)) {
            throw protocolFailure("claim image deletion", -4L);
        }
        if ("BUSY".equals(claim)) {
            return false;
        }
        if (claim == null || !claim.startsWith("CLAIMED:")) {
            throw new IllegalStateException("Unable to claim image deletion in Redis");
        }
        Long verified = redisTemplate.execute(
                VERIFY_DELETION_SCRIPT,
                List.of(COUNTS_HASH, TOMBSTONES_HASH, META_HASH),
                path,
                READY_FIELD,
                PROTOCOL_FIELD,
                PROTOCOL_VERSION,
                claim);
        if (verified == null) {
            throw new IllegalStateException("Unable to validate image deletion claim in Redis");
        }
        if (verified != 1L) {
            abandonDeletionClaim(path, claim);
            return false;
        }
        boolean deleted;
        try {
            deleted = Boolean.TRUE.equals(physicalDeletion.get());
        } catch (RuntimeException | Error failure) {
            try {
                finalizeDeletion(path, claim, false);
            } catch (RuntimeException finalizationFailure) {
                failure.addSuppressed(finalizationFailure);
            }
            throw failure;
        }
        finalizeDeletion(path, claim, deleted);
        return deleted;
    }

    @Override
    public DeletionMaintenanceResult maintainDeletionState(
            Collection<String> authoritativeReferences,
            Collection<String> existingStorageReferences,
            long nowEpochMillis) {
        if (!authoritativeSnapshotReady()) {
            return DeletionMaintenanceResult.NONE;
        }
        Set<String> referenced = canonicalPaths(authoritativeReferences);
        Set<String> existing = canonicalPaths(existingStorageReferences);
        int recoveredClaims = 0;
        int markedDeleted = 0;
        int compactedTombstones = 0;
        int transitions = 0;
        HashOperations<String, Object, Object> tombstones = redisTemplate.opsForHash();
        ScanOptions scanOptions =
                ScanOptions.scanOptions().count(maintenanceBatchSize).build();
        try (Cursor<Map.Entry<Object, Object>> cursor = tombstones.scan(TOMBSTONES_HASH, scanOptions)) {
            while (cursor.hasNext()) {
                Map.Entry<Object, Object> entry = cursor.next();
                String path = entry.getKey().toString();
                String marker = entry.getValue().toString();
                if (referenced.contains(path)) {
                    continue;
                }
                String operation = null;
                if (existing.contains(path)) {
                    if (marker.startsWith("DELETED:")) {
                        operation = "RETRY";
                    }
                } else if (marker.startsWith("CLAIMED:")) {
                    operation = "RECOVER";
                } else if (marker.startsWith("RETRYABLE:")) {
                    operation = "MARK";
                } else if (marker.startsWith("DELETED:")) {
                    operation = "COMPACT";
                }
                if (operation == null || !maintainTombstone(path, marker, operation)) {
                    continue;
                }
                transitions++;
                if ("RECOVER".equals(operation)) {
                    recoveredClaims++;
                    markedDeleted++;
                } else if ("MARK".equals(operation)) {
                    markedDeleted++;
                } else if ("COMPACT".equals(operation)) {
                    compactedTombstones++;
                }
                if (transitions >= maintenanceBatchSize) {
                    break;
                }
            }
        }
        return new DeletionMaintenanceResult(recoveredClaims, markedDeleted, compactedTombstones);
    }

    @Override
    public <T> T withMutationFence(Supplier<T> work) {
        if (redissonClient == null) {
            synchronized (this) {
                return work.get();
            }
        }
        RLock lock = redissonClient.getLock(MUTATION_LOCK_KEY);
        lock.lock();
        try {
            return work.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private boolean publishIfUnchanged(Collection<String> imagePaths, long expectedVersion) {
        Map<String, String> replacement = replacement(imagePaths);
        String stagingKey = STAGING_PREFIX + UUID.randomUUID();
        List<String> stageArguments = new ArrayList<>(replacement.size() * 2 + 2);
        stageArguments.add(Long.toString(STAGING_TTL.toMillis()));
        stageArguments.add(STAGING_SENTINEL);
        replacement.forEach((path, count) -> {
            stageArguments.add(path);
            stageArguments.add(count);
        });
        Long staged = redisTemplate.execute(
                STAGE_SNAPSHOT_SCRIPT, List.of(stagingKey), stageArguments.toArray(Object[]::new));
        if (staged == null || staged != 1L) {
            throw new IllegalStateException("Unable to stage image reference snapshot in Redis");
        }
        try {
            Long published = redisTemplate.execute(
                    PUBLISH_IF_UNCHANGED_SCRIPT,
                    List.of(COUNTS_HASH, TOMBSTONES_HASH, META_HASH, stagingKey),
                    Long.toString(expectedVersion),
                    VERSION_FIELD,
                    READY_FIELD,
                    PROTOCOL_FIELD,
                    PROTOCOL_VERSION,
                    STAGING_SENTINEL);
            if (published == null || published == -4L) {
                throw protocolFailure("publish image reference snapshot", published);
            }
            if (published == -1L) {
                throw new IllegalStateException("Authoritative image snapshot contains a deleted path");
            }
            if (published == -2L) {
                throw new IllegalStateException("Image reference staged snapshot is missing or incomplete");
            }
            if (published < 0L) {
                throw new IllegalStateException("Unable to publish image reference snapshot in Redis");
            }
            return published == 1L;
        } finally {
            redisTemplate.delete(stagingKey);
        }
    }

    private void finalizeDeletion(String path, String claim, boolean deleted) {
        Long finalized = redisTemplate.execute(
                FINALIZE_DELETION_SCRIPT, List.of(TOMBSTONES_HASH), path, claim, deleted ? "1" : "0");
        if (finalized == null) {
            throw new IllegalStateException("Unable to finalize image deletion claim in Redis");
        }
        if (finalized != 1L) {
            log.warn("Image deletion claim was taken over before the previous owner finalized it");
        }
    }

    private void abandonDeletionClaim(String path, String claim) {
        Long abandoned = redisTemplate.execute(ABANDON_DELETION_SCRIPT, List.of(TOMBSTONES_HASH), path, claim);
        if (abandoned == null) {
            throw new IllegalStateException("Unable to abandon image deletion claim in Redis");
        }
        if (abandoned != 1L) {
            log.warn("Image deletion claim was taken over before the previous owner abandoned it");
        }
    }

    private boolean maintainTombstone(String path, String expectedMarker, String operation) {
        Long maintained = redisTemplate.execute(
                MAINTAIN_TOMBSTONE_SCRIPT,
                List.of(COUNTS_HASH, TOMBSTONES_HASH, META_HASH),
                path,
                expectedMarker,
                READY_FIELD,
                PROTOCOL_FIELD,
                PROTOCOL_VERSION,
                operation,
                Long.toString(deletionClaimStaleAfter.toMillis()),
                Long.toString(deletionTombstoneRetention.toMillis()));
        if (maintained == null || maintained == -3L) {
            throw new IllegalStateException("Authoritative image snapshot became unavailable during maintenance");
        }
        return maintained == 1L;
    }

    private Map<String, String> replacement(Collection<String> imagePaths) {
        Map<String, String> replacement = new LinkedHashMap<>();
        if (imagePaths != null) {
            imagePaths.stream()
                    .filter(ImageReferenceService::isTrackable)
                    .map(path -> managedPath(path, false))
                    .filter(java.util.Objects::nonNull)
                    .forEach(path -> replacement.merge(
                            path, "1", (left, right) -> Long.toString(Long.parseLong(left) + Long.parseLong(right))));
        }
        return replacement;
    }

    private Set<String> canonicalPaths(Collection<String> imagePaths) {
        Set<String> paths = new HashSet<>();
        if (imagePaths != null) {
            imagePaths.stream()
                    .filter(ImageReferenceService::isTrackable)
                    .map(path -> managedPath(path, false))
                    .filter(java.util.Objects::nonNull)
                    .forEach(paths::add);
        }
        return paths;
    }

    private String managedPath(String imageReference, boolean requireExisting) {
        if (objectStorageService == null) {
            return canonicalPath(imageReference);
        }
        String objectKey;
        try {
            objectKey = objectStorageService.resolveObjectKey(imageReference);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Unable to resolve managed image reference", failure);
        }
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        if (requireExisting) {
            try {
                if (!objectStorageService.exists(objectKey)) {
                    throw new IllegalStateException("Managed image object does not exist");
                }
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to verify managed image object", failure);
            }
        }
        return canonicalPath(objectKey);
    }

    private static String canonicalPath(String imagePath) {
        return ImageReferenceService.canonicalPath(imagePath.trim());
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }

    private static void assertCompatibleAndSuccessful(Long result, String operation) {
        if (result == null || result == -4L || result <= 0L) {
            throw protocolFailure(operation, result);
        }
    }

    private static IllegalStateException protocolFailure(String operation, Long result) {
        if (result != null && result == -4L) {
            return new IllegalStateException("Incompatible Redis image-reference protocol version");
        }
        return new IllegalStateException("Unable to " + operation + " in Redis");
    }

    private static long parseNonNegative(Object value) {
        if (value == null) {
            return 0L;
        }
        long parsed = Long.parseLong(value.toString());
        if (parsed < 0L) {
            throw new IllegalStateException("Negative image reference version in Redis");
        }
        return parsed;
    }
}

package com.example.monkey.shared.infrastructure.storage;

import com.example.monkey.shared.domain.storage.ImageReferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisImageReferenceService implements ImageReferenceService {

    private static final Logger log = LoggerFactory.getLogger(RedisImageReferenceService.class);
    private static final String REFCOUNT_HASH = "monkeyshop:image:refcount";

    private final StringRedisTemplate redisTemplate;

    public RedisImageReferenceService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void retain(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        try {
            redisTemplate.opsForHash().increment(REFCOUNT_HASH, imagePath, 1L);
        } catch (RuntimeException e) {
            log.warn("Unable to retain image reference in Redis");
            log.debug("Image reference retain failure", e);
        }
    }

    @Override
    public void release(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return;
        }
        try {
            Long count = redisTemplate.opsForHash().increment(REFCOUNT_HASH, imagePath, -1L);
            if (count == null || count <= 0L) {
                redisTemplate.opsForHash().delete(REFCOUNT_HASH, imagePath);
            }
        } catch (RuntimeException e) {
            log.warn("Unable to release image reference in Redis");
            log.debug("Image reference release failure", e);
        }
    }

    @Override
    public long referenceCount(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return 0L;
        }
        try {
            Object value = redisTemplate.opsForHash().get(REFCOUNT_HASH, imagePath);
            if (value == null) {
                return 0L;
            }
            return Math.max(0L, Long.parseLong(value.toString()));
        } catch (RuntimeException e) {
            log.warn("Unable to read image reference count from Redis");
            log.debug("Image reference count failure", e);
            return 0L;
        }
    }

    @Override
    public void clear() {
        try {
            redisTemplate.delete(REFCOUNT_HASH);
        } catch (RuntimeException e) {
            log.warn("Unable to clear image reference counts in Redis");
            log.debug("Image reference clear failure", e);
        }
    }
}

package com.example.monkey.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RedisImageReferenceServiceTest {

    private static final String REFCOUNT_HASH = "monkeyshop:image:refcount";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private RedisImageReferenceService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        service = new RedisImageReferenceService(redisTemplate);
    }

    @Test
    void retainIncrementsRedisHash() {
        service.retain("/images/avatar/alice.png");

        verify(hashOperations).increment(REFCOUNT_HASH, "/images/avatar/alice.png", 1L);
    }

    @Test
    void releaseDeletesHashEntryAtZero() {
        when(hashOperations.increment(REFCOUNT_HASH, "/images/avatar/alice.png", -1L))
                .thenReturn(0L);

        service.release("/images/avatar/alice.png");

        verify(hashOperations).delete(REFCOUNT_HASH, "/images/avatar/alice.png");
    }

    @Test
    void referenceCountParsesRedisValue() {
        when(hashOperations.get(REFCOUNT_HASH, "/images/avatar/alice.png")).thenReturn("3");

        assertThat(service.referenceCount("/images/avatar/alice.png")).isEqualTo(3L);
    }

    @Test
    void rebuildReplacesHashAndRetainsTrackableValues() {
        service.rebuild(List.of("/images/avatar/alice.png", "/images/default_avatar.png"));

        verify(redisTemplate).delete(REFCOUNT_HASH);
        verify(hashOperations).increment(REFCOUNT_HASH, "/images/avatar/alice.png", 1L);
    }
}

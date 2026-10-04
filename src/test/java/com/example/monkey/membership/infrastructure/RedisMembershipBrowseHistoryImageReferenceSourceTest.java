package com.example.monkey.membership.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.membership.domain.BrowseHistoryItem;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class RedisMembershipBrowseHistoryImageReferenceSourceTest {

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void fallbackPathEmitsEveryLiveProductImageWithMultiplicityAndTenantIsolation() {
        RedisMembershipActivityStore activityStore = new RedisMembershipActivityStore(noRedis(), objectMapper());
        RedisMembershipBrowseHistoryImageReferenceSource source =
                new RedisMembershipBrowseHistoryImageReferenceSource(activityStore);
        LocalDateTime now = LocalDateTime.now().minusMinutes(2);

        TenantContext.setTenantId(11L);
        activityStore.record(item(1L, 100L, "/images/shared.png", now, now.plusHours(1)), Duration.ofHours(1));
        activityStore.record(
                item(2L, 101L, "/images/shared.png", now.plusSeconds(1), now.plusHours(1)), Duration.ofHours(1));
        activityStore.record(
                item(3L, 102L, "/images/expired.png", now.minusHours(2), now.minusMinutes(1)), Duration.ofHours(1));
        TenantContext.setTenantId(12L);
        activityStore.record(item(4L, 100L, "/images/other-tenant.png", now, now.plusHours(1)), Duration.ofHours(1));

        TenantContext.setTenantId(11L);
        List<String> references = new ArrayList<>();
        source.forEachReferencedImagePath(references::add);

        assertThat(references).containsExactlyInAnyOrder("/images/shared.png", "/images/shared.png");
        assertThat(source.isUsed("/images/shared.png")).isTrue();
        assertThat(source.isUsed("/images/other-tenant.png")).isFalse();
    }

    @Test
    void redisReadFailurePropagatesForCleanupInsteadOfUsingIncompleteFallback() {
        ObjectProvider<StringRedisTemplate> provider = mock();
        StringRedisTemplate redisTemplate = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.scan(any(ScanOptions.class))).thenThrow(new IllegalStateException("Redis unavailable"));
        RedisMembershipActivityStore activityStore = new RedisMembershipActivityStore(provider, objectMapper());
        RedisMembershipBrowseHistoryImageReferenceSource source =
                new RedisMembershipBrowseHistoryImageReferenceSource(activityStore);
        LocalDateTime now = LocalDateTime.now().minusMinutes(2);

        TenantContext.setTenantId(11L);
        activityStore.record(item(1L, 100L, "/images/fallback.png", now, now.plusHours(1)), Duration.ofHours(1));

        assertThatThrownBy(() -> source.isUsed("/images/fallback.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("browse history");
        assertThatThrownBy(() -> source.forEachReferencedImagePath(ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("browse history");
    }

    @Test
    void successfulRedisScanStillIncludesAPreviouslyFailedWriteFromFallback() {
        ObjectProvider<StringRedisTemplate> provider = mock();
        StringRedisTemplate redisTemplate = mock();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSet = mock();
        @SuppressWarnings("unchecked")
        Cursor<String> keys = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForZSet()).thenReturn(zSet);
        when(zSet.range(anyString(), eq(0L), eq(-1L))).thenThrow(new IllegalStateException("Redis unavailable"));
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(keys);
        when(keys.hasNext()).thenReturn(false);
        RedisMembershipActivityStore activityStore = new RedisMembershipActivityStore(provider, objectMapper());
        RedisMembershipBrowseHistoryImageReferenceSource source =
                new RedisMembershipBrowseHistoryImageReferenceSource(activityStore);
        LocalDateTime now = LocalDateTime.now().minusMinutes(2);
        TenantContext.setTenantId(11L);
        activityStore.record(item(1L, 100L, "/images/fallback.png", now, now.plusHours(1)), Duration.ofHours(1));

        List<String> references = new ArrayList<>();
        source.forEachReferencedImagePath(references::add);

        assertThat(references).containsExactly("/images/fallback.png");
    }

    @Test
    void redisDeserializationFailurePropagatesForCleanup() {
        ObjectProvider<StringRedisTemplate> provider = mock();
        StringRedisTemplate redisTemplate = mock();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSet = mock();
        @SuppressWarnings("unchecked")
        Cursor<String> keys = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(keys);
        when(keys.hasNext()).thenReturn(true, false);
        when(keys.next()).thenReturn("membership:browse:tenant:11:user:100");
        when(redisTemplate.opsForZSet()).thenReturn(zSet);
        when(zSet.range(eq("membership:browse:tenant:11:user:100"), eq(0L), eq(-1L)))
                .thenReturn(Set.of("not-json"));
        RedisMembershipBrowseHistoryImageReferenceSource source = new RedisMembershipBrowseHistoryImageReferenceSource(
                new RedisMembershipActivityStore(provider, objectMapper()));
        TenantContext.setTenantId(11L);

        assertThatThrownBy(() -> source.forEachReferencedImagePath(ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("browse history");
    }

    @Test
    void redisPathReadsOnlyTheCapturedTenantKeysAndSkipsExpiredRows() {
        ObjectProvider<StringRedisTemplate> provider = mock();
        StringRedisTemplate redisTemplate = mock();
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSet = mock();
        @SuppressWarnings("unchecked")
        Cursor<String> keys = mock();
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(keys);
        when(keys.hasNext()).thenReturn(true, false);
        when(keys.next()).thenReturn("membership:browse:tenant:11:user:100");
        when(redisTemplate.opsForZSet()).thenReturn(zSet);
        when(zSet.range(eq("membership:browse:tenant:11:user:100"), eq(0L), eq(-1L)))
                .thenReturn(Set.of(
                        serialize(item(
                                1L,
                                100L,
                                "/images/live.png",
                                LocalDateTime.now(),
                                LocalDateTime.now().plusHours(1))),
                        serialize(item(
                                2L,
                                100L,
                                "/images/expired.png",
                                LocalDateTime.now().minusHours(2),
                                LocalDateTime.now().minusMinutes(1)))));
        RedisMembershipActivityStore activityStore = new RedisMembershipActivityStore(provider, objectMapper());
        RedisMembershipBrowseHistoryImageReferenceSource source =
                new RedisMembershipBrowseHistoryImageReferenceSource(activityStore);
        TenantContext.setTenantId(11L);
        List<String> references = new ArrayList<>();

        source.forEachReferencedImagePath(references::add);

        assertThat(references).containsExactly("/images/live.png");
    }

    private static BrowseHistoryItem item(
            Long id, Long userId, String image, LocalDateTime viewedAt, LocalDateTime expiresAt) {
        return new BrowseHistoryItem(id, userId, id, "product-" + id, image, viewedAt, expiresAt);
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static String serialize(BrowseHistoryItem item) {
        try {
            return objectMapper().writeValueAsString(item);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static ObjectProvider<StringRedisTemplate> noRedis() {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject(Object... args) {
                throw new IllegalStateException("Redis is not configured");
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return null;
            }

            @Override
            public StringRedisTemplate getIfUnique() {
                return null;
            }

            @Override
            public StringRedisTemplate getObject() {
                throw new IllegalStateException("Redis is not configured");
            }
        };
    }
}

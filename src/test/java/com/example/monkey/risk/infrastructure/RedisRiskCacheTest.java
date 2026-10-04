package com.example.monkey.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.risk.domain.RiskDecision;
import com.example.monkey.risk.domain.RiskScore;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRiskCacheTest {

    private RedisRiskCache cache;

    @BeforeEach
    void setUp() {
        cache = new RedisRiskCache((StringRedisTemplate) null, new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void fallbackTracksDeviceUsersPhonesSeckillAndScores() {
        cache.rememberDeviceFingerprint("device-hmac", 1L, "phone-a", Duration.ofDays(30));
        cache.rememberDeviceFingerprint("device-hmac", 2L, "phone-b", Duration.ofDays(30));

        assertThat(cache.countUsersForDevice("device-hmac")).isEqualTo(2);
        assertThat(cache.countPhonesForDevice("device-hmac")).isEqualTo(2);

        assertThat(cache.recordSeckillAttempt(10L, 20L, "device-hmac", 1L, Duration.ofMinutes(5)))
                .isEqualTo(1);
        assertThat(cache.recordSeckillAttempt(10L, 20L, "device-hmac", 2L, Duration.ofMinutes(5)))
                .isEqualTo(2);

        RiskScore score = new RiskScore(
                99L,
                2L,
                "device-hmac",
                "phone-b",
                80,
                RiskDecision.TOTP_REQUIRED,
                List.of(),
                LocalDateTime.now(),
                LocalDateTime.now().plusMinutes(30),
                0);
        cache.cacheScore(score, Duration.ofMinutes(30));

        assertThat(cache.findScore(2L)).contains(score);
    }

    @Test
    void fallbackRiskSignalsRemainIsolatedByTenant() {
        RiskScore tenantOneScore = riskScore(77L, 10);
        RiskScore tenantTwoScore = riskScore(77L, 90);

        TenantContext.setTenantId(1L);
        cache.rememberDeviceFingerprint("same-device", 101L, "phone-one", Duration.ofDays(30));
        assertThat(cache.countUsersForDevice("same-device")).isEqualTo(1);
        assertThat(cache.countPhonesForDevice("same-device")).isEqualTo(1);
        assertThat(cache.recordSeckillAttempt(10L, 20L, "same-device", 101L, Duration.ofMinutes(5)))
                .isEqualTo(1);
        cache.cacheScore(tenantOneScore, Duration.ofMinutes(30));

        TenantContext.setTenantId(2L);
        cache.rememberDeviceFingerprint("same-device", 202L, "phone-two", Duration.ofDays(30));
        assertThat(cache.countUsersForDevice("same-device")).isEqualTo(1);
        assertThat(cache.countPhonesForDevice("same-device")).isEqualTo(1);
        assertThat(cache.recordSeckillAttempt(10L, 20L, "same-device", 202L, Duration.ofMinutes(5)))
                .isEqualTo(1);
        cache.cacheScore(tenantTwoScore, Duration.ofMinutes(30));
        assertThat(cache.findScore(77L)).contains(tenantTwoScore);

        TenantContext.setTenantId(1L);
        assertThat(cache.countUsersForDevice("same-device")).isEqualTo(1);
        assertThat(cache.countPhonesForDevice("same-device")).isEqualTo(1);
        assertThat(cache.recordSeckillAttempt(10L, 20L, "same-device", 101L, Duration.ofMinutes(5)))
                .isEqualTo(1);
        assertThat(cache.findScore(77L)).contains(tenantOneScore);
    }

    @Test
    void redisRiskKeysContainTenantNamespaceForAllSignalTypes() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        List<String> seenKeys = new ArrayList<>();
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(setOperations.add(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    seenKeys.add(invocation.getArgument(0));
                    return true;
                });
        when(setOperations.size(anyString()))
                .thenAnswer(invocation -> {
                    seenKeys.add(invocation.getArgument(0));
                    return 0L;
                });
        when(redisTemplate.expire(anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    seenKeys.add(invocation.getArgument(0));
                    return true;
                });
        doAnswer(invocation -> {
                    seenKeys.add(invocation.getArgument(0));
                    return null;
                })
                .when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));
        when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> {
                    seenKeys.add(invocation.getArgument(0));
                    return null;
                });

        RedisRiskCache redisCache = new RedisRiskCache(redisTemplate, new ObjectMapper().findAndRegisterModules());
        RiskScore score = riskScore(77L, 10);
        TenantContext.setTenantId(1L);
        redisCache.rememberDeviceFingerprint("same-device", 101L, "phone-one", Duration.ofDays(30));
        redisCache.countUsersForDevice("same-device");
        redisCache.countPhonesForDevice("same-device");
        redisCache.recordSeckillAttempt(10L, 20L, "same-device", 101L, Duration.ofMinutes(5));
        redisCache.cacheScore(score, Duration.ofMinutes(30));
        redisCache.findScore(77L);

        TenantContext.setTenantId(2L);
        redisCache.rememberDeviceFingerprint("same-device", 202L, "phone-two", Duration.ofDays(30));
        redisCache.countUsersForDevice("same-device");
        redisCache.countPhonesForDevice("same-device");
        redisCache.recordSeckillAttempt(10L, 20L, "same-device", 202L, Duration.ofMinutes(5));
        redisCache.cacheScore(score, Duration.ofMinutes(30));
        redisCache.findScore(77L);

        assertThat(seenKeys).contains(
                "risk:device:tenant:1:same-device:users",
                "risk:device:tenant:2:same-device:users",
                "risk:device:tenant:1:same-device:phones",
                "risk:device:tenant:2:same-device:phones",
                "risk:seckill:tenant:1:10:20:device:same-device",
                "risk:seckill:tenant:2:10:20:device:same-device",
                "risk:score:user:1:77",
                "risk:score:user:2:77");
    }

    @Test
    void requiredRedisModeRejectsAProcessLocalFallback() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        assertThatThrownBy(() -> new RedisRiskCache((StringRedisTemplate) null, objectMapper, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis");
    }

    @Test
    void requiredRedisModeFailsClosedWhenSharedStateCannotBeRead() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForSet()).thenThrow(new IllegalStateException("Redis unavailable"));
        RedisRiskCache requiredCache =
                new RedisRiskCache(redisTemplate, new ObjectMapper().findAndRegisterModules(), true);

        assertThatThrownBy(() -> requiredCache.countUsersForDevice("same-device"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    private static RiskScore riskScore(Long userId, int score) {
        LocalDateTime assessedAt = LocalDateTime.parse("2026-01-01T00:00:00");
        return new RiskScore(
                99L,
                userId,
                "same-device",
                "same-phone",
                score,
                RiskDecision.TOTP_REQUIRED,
                List.of(),
                assessedAt,
                assessedAt.plusMinutes(30),
                0);
    }
}

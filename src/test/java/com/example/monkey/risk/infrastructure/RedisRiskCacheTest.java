package com.example.monkey.risk.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.risk.domain.RiskDecision;
import com.example.monkey.risk.domain.RiskScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisRiskCacheTest {

    private final RedisRiskCache cache =
            new RedisRiskCache((StringRedisTemplate) null, new ObjectMapper().findAndRegisterModules());

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
}

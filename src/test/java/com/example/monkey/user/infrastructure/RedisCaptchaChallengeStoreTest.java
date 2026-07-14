package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@ExtendWith(MockitoExtension.class)
class RedisCaptchaChallengeStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void unavailableWhenRedisTemplateIsMissing() {
        RedisCaptchaChallengeStore store = new RedisCaptchaChallengeStore((StringRedisTemplate) null);

        assertThat(store.available()).isFalse();
        assertThat(store.consume("challenge-id")).isEmpty();
        assertThatCode(() -> store.store("challenge-id", "ABCD", Duration.ofMinutes(5)))
                .doesNotThrowAnyException();
    }

    @Test
    void storeWritesCaptchaCodeWithTtl() {
        RedisCaptchaChallengeStore store = new RedisCaptchaChallengeStore(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        store.store("challenge-id", "ABCD", Duration.ofMinutes(5));

        verify(valueOperations).set("captcha:challenge-id", "ABCD", Duration.ofMinutes(5));
    }

    @Test
    void consumeAtomicallyReadsAndDeletesCaptchaCode() {
        RedisCaptchaChallengeStore store = new RedisCaptchaChallengeStore(redisTemplate);
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("captcha:challenge-id"))))
                .thenReturn("ABCD");

        Optional<String> code = store.consume("challenge-id");

        assertThat(code).contains("ABCD");
        verify(redisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void atomicConsumeReturnsEmptyWhenCaptchaKeyIsMissing() {
        RedisCaptchaChallengeStore store = new RedisCaptchaChallengeStore(redisTemplate);
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(List.of("captcha:challenge-id"))))
                .thenReturn(null);

        Optional<String> code = store.consume("challenge-id");

        assertThat(code).isEmpty();
        verify(redisTemplate, never()).delete(anyString());
    }
}

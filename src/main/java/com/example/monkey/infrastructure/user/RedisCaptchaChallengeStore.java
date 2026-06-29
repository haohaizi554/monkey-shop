package com.example.monkey.infrastructure.user;

import com.example.monkey.domain.user.CaptchaChallengeStore;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisCaptchaChallengeStore implements CaptchaChallengeStore {

    private static final String REDIS_CAPTCHA_PREFIX = "captcha:";

    private final StringRedisTemplate redisTemplate;

    public RedisCaptchaChallengeStore(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        this(redisTemplateProvider.getIfAvailable());
    }

    RedisCaptchaChallengeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean available() {
        return redisTemplate != null;
    }

    @Override
    public void store(String challengeId, String code, Duration ttl) {
        if (!available()) {
            return;
        }
        redisTemplate.opsForValue().set(redisKey(challengeId), code, ttl);
    }

    @Override
    public Optional<String> consume(String challengeId) {
        if (!available()) {
            return Optional.empty();
        }
        String redisKey = redisKey(challengeId);
        String code = redisTemplate.opsForValue().get(redisKey);
        redisTemplate.delete(redisKey);
        return Optional.ofNullable(code);
    }

    private static String redisKey(String challengeId) {
        return REDIS_CAPTCHA_PREFIX + challengeId;
    }
}

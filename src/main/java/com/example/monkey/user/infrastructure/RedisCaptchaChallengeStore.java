package com.example.monkey.user.infrastructure;

import com.example.monkey.user.domain.CaptchaChallengeStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisCaptchaChallengeStore implements CaptchaChallengeStore {

    private static final String REDIS_CAPTCHA_PREFIX = "captcha:";
    private static final DefaultRedisScript<String> GET_AND_DELETE = new DefaultRedisScript<>(
            "local value=redis.call('GET',KEYS[1]); redis.call('DEL',KEYS[1]); return value", String.class);

    private final StringRedisTemplate redisTemplate;

    @Autowired
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
        return Optional.ofNullable(redisTemplate.execute(GET_AND_DELETE, List.of(redisKey(challengeId))));
    }

    private static String redisKey(String challengeId) {
        return REDIS_CAPTCHA_PREFIX + challengeId;
    }
}

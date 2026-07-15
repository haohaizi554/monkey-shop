package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartCleanupIntent;
import com.example.monkey.cart.domain.CartCleanupIntentStatus;
import com.example.monkey.cart.domain.CartCleanupIntentStore;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class JpaCartCleanupIntentStore implements CartCleanupIntentStore {

    private final CartCleanupIntentRepository repository;

    public JpaCartCleanupIntentStore(CartCleanupIntentRepository repository) {
        this.repository = repository;
    }

    @Override
    public CartCleanupIntent save(CartCleanupIntent intent) {
        CartCleanupIntentEntity entity =
                repository.findById(intent.checkoutId()).orElseGet(CartCleanupIntentEntity::new);
        entity.setCheckoutId(intent.checkoutId());
        entity.setUserId(intent.userId());
        entity.setSkuIds(
                intent.skuIds().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        entity.setCartTtlSeconds(intent.cartTtlSeconds());
        entity.setStatus(intent.status());
        entity.setAttemptCount(intent.attemptCount());
        entity.setNextAttemptAt(intent.nextAttemptAt());
        entity.setLastError(intent.lastError());
        entity.setCreateTime(intent.createdAt());
        entity.setUpdateTime(intent.updatedAt());
        entity.setCompletedAt(intent.completedAt());
        return toDomain(repository.save(entity));
    }

    @Override
    public Optional<CartCleanupIntent> findByCheckoutId(Long checkoutId) {
        return repository.findById(checkoutId).map(JpaCartCleanupIntentStore::toDomain);
    }

    @Override
    public List<Long> findReadyCheckoutIds(LocalDateTime now) {
        return repository
                .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreateTimeAsc(
                        CartCleanupIntentStatus.PENDING, now)
                .stream()
                .map(CartCleanupIntentEntity::getCheckoutId)
                .toList();
    }

    private static CartCleanupIntent toDomain(CartCleanupIntentEntity entity) {
        List<Long> skuIds = StringUtils.hasText(entity.getSkuIds())
                ? Arrays.stream(entity.getSkuIds().split(","))
                        .map(Long::valueOf)
                        .toList()
                : List.of();
        return new CartCleanupIntent(
                entity.getCheckoutId(),
                entity.getUserId(),
                skuIds,
                entity.getCartTtlSeconds(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getNextAttemptAt(),
                entity.getLastError(),
                entity.getCreateTime(),
                entity.getUpdateTime(),
                entity.getCompletedAt());
    }
}

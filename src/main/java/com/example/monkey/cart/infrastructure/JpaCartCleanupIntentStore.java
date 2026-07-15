package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartCleanupIntent;
import com.example.monkey.cart.domain.CartCleanupIntentStore;
import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JpaCartCleanupIntentStore implements CartCleanupIntentStore {

    private final CartCleanupIntentRepository repository;
    private final ObjectMapper objectMapper;

    public JpaCartCleanupIntentStore(CartCleanupIntentRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public CartCleanupIntent save(CartCleanupIntent intent) {
        CartCleanupIntentEntity entity =
                repository.findById(intent.checkoutId()).orElseGet(CartCleanupIntentEntity::new);
        entity.setCheckoutId(intent.checkoutId());
        entity.setUserId(intent.userId());
        entity.setItemSnapshotsJson(serialize(intent.itemSnapshots()));
        entity.setCartTtlSeconds(intent.cartTtlSeconds());
        entity.setStatus(intent.status());
        entity.setAttemptCount(intent.attemptCount());
        entity.setNextAttemptAt(intent.nextAttemptAt());
        entity.setClaimToken(intent.claimToken());
        entity.setLeaseExpiresAt(intent.leaseExpiresAt());
        entity.setLastError(intent.lastError());
        entity.setCreateTime(intent.createdAt());
        entity.setUpdateTime(intent.updatedAt());
        entity.setCompletedAt(intent.completedAt());
        return toDomain(repository.save(entity));
    }

    @Override
    public Optional<CartCleanupIntent> findByCheckoutId(Long checkoutId) {
        return repository.findById(checkoutId).map(this::toDomain);
    }

    @Override
    public Optional<CartCleanupIntent> claim(
            Long checkoutId, String claimToken, LocalDateTime now, LocalDateTime leaseExpiresAt) {
        int claimed =
                repository.claim(checkoutId, TenantContext.currentTenantIdOrDefault(), claimToken, now, leaseExpiresAt);
        return claimed == 1 ? findByCheckoutId(checkoutId) : Optional.empty();
    }

    @Override
    public boolean completeClaim(Long checkoutId, String claimToken, LocalDateTime now) {
        return repository.completeClaim(checkoutId, TenantContext.currentTenantIdOrDefault(), claimToken, now) == 1;
    }

    @Override
    public boolean failClaim(
            Long checkoutId, String claimToken, LocalDateTime now, LocalDateTime nextAttemptAt, String error) {
        return repository.failClaim(
                        checkoutId, TenantContext.currentTenantIdOrDefault(), claimToken, now, nextAttemptAt, error)
                == 1;
    }

    @Override
    public List<Long> findReadyCheckoutIds(LocalDateTime now) {
        return repository.findReadyCheckoutIds(TenantContext.currentTenantIdOrDefault(), now);
    }

    private CartCleanupIntent toDomain(CartCleanupIntentEntity entity) {
        return new CartCleanupIntent(
                entity.getCheckoutId(),
                entity.getUserId(),
                deserialize(entity.getItemSnapshotsJson()),
                entity.getCartTtlSeconds(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getNextAttemptAt(),
                entity.getClaimToken(),
                entity.getLeaseExpiresAt(),
                entity.getLastError(),
                entity.getCreateTime(),
                entity.getUpdateTime(),
                entity.getCompletedAt());
    }

    private String serialize(List<CartItem> itemSnapshots) {
        try {
            return objectMapper.writeValueAsString(itemSnapshots);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cart cleanup snapshots cannot be serialized", exception);
        }
    }

    private List<CartItem> deserialize(String storedJson) {
        try {
            JsonNode node = objectMapper.readTree(storedJson);
            if (!node.isArray()) {
                throw new IllegalStateException("Cart cleanup snapshots must be a JSON array");
            }
            return objectMapper.readerForListOf(CartItem.class).readValue(node);
        } catch (IOException exception) {
            throw new IllegalStateException("Cart cleanup snapshots cannot be deserialized", exception);
        }
    }
}

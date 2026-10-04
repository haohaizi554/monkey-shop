package com.example.monkey.marketing.infrastructure;

import com.example.monkey.marketing.domain.GroupBuyIdempotencyBinding;
import com.example.monkey.marketing.domain.GroupBuyIdempotencyBindingStore;
import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** MySQL durable authority for group-buy idempotency bindings. */
@Component
@ConditionalOnProperty(name = "app.marketing.store", havingValue = "jpa", matchIfMissing = true)
public class JpaMarketingGroupBuyIdempotencyBindingStore implements GroupBuyIdempotencyBindingStore {

    private final MarketingGroupBuyIdempotencyBindingRepository repository;

    public JpaMarketingGroupBuyIdempotencyBindingStore(MarketingGroupBuyIdempotencyBindingRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<GroupBuyIdempotencyBinding> find(Long tenantId, Long userId, String idempotencyKey) {
        requireCurrentTenant(tenantId);
        return repository
                .findByTenantIdAndUserIdAndIdempotencyKey(tenantId, userId, idempotencyKey)
                .map(JpaMarketingGroupBuyIdempotencyBindingStore::toDomain);
    }

    @Override
    public boolean reserve(GroupBuyIdempotencyBinding binding) {
        requireCurrentTenant(binding.tenantId());
        return repository.insertIfAbsent(
                        binding.tenantId(),
                        binding.userId(),
                        binding.idempotencyKey(),
                        binding.teamId(),
                        binding.requestFingerprint(),
                        binding.createdAt(),
                        binding.updatedAt())
                > 0;
    }

    private static void requireCurrentTenant(Long tenantId) {
        if (tenantId == null || tenantId != TenantContext.currentTenantIdOrDefault()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Group-buy binding tenant does not match request tenant");
        }
    }

    private static GroupBuyIdempotencyBinding toDomain(MarketingGroupBuyIdempotencyBindingEntity entity) {
        return new GroupBuyIdempotencyBinding(
                entity.getTenantId(),
                entity.getUserId(),
                entity.getIdempotencyKey(),
                entity.getTeamId(),
                entity.getRequestFingerprint(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}

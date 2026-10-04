package com.example.monkey.product.infrastructure;

import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.membership.domain.MembershipStore;
import com.example.monkey.product.domain.PriceContext;
import com.example.monkey.product.domain.ProductPriceContextResolver;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class MembershipProductPriceContextResolver implements ProductPriceContextResolver {

    private final MembershipStore membershipStore;

    public MembershipProductPriceContextResolver(MembershipStore membershipStore) {
        this.membershipStore = membershipStore;
    }

    @Override
    public PriceContext resolve(Long userId, String region) {
        String normalizedRegion = region == null ? "" : region.trim().toUpperCase(Locale.ROOT);
        if (userId == null) {
            return new PriceContext("ANONYMOUS", normalizedRegion);
        }
        String identity = membershipStore
                .findProfile(userId)
                .filter(profile -> profile.level() != MembershipLevel.BASIC)
                .map(ignored -> "MEMBER")
                .orElse("BASIC");
        return new PriceContext(identity, normalizedRegion);
    }
}

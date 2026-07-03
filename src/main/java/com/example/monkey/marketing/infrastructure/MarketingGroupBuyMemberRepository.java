package com.example.monkey.marketing.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketingGroupBuyMemberRepository extends JpaRepository<MarketingGroupBuyMemberEntity, Long> {

    boolean existsByTeamIdAndUserId(Long teamId, Long userId);
}

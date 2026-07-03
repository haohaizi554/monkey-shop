package com.example.monkey.marketing.infrastructure;

import com.example.monkey.marketing.domain.GroupBuyStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketingGroupBuyTeamRepository extends JpaRepository<MarketingGroupBuyTeamEntity, Long> {

    List<MarketingGroupBuyTeamEntity> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            GroupBuyStatus status, LocalDateTime now);
}

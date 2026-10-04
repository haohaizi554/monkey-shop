package com.example.monkey.marketing.infrastructure;

import com.example.monkey.marketing.application.MarketingApplicationService;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MarketingGroupBuyExpiryTask {

    private final MarketingApplicationService marketingApplicationService;
    private final ActiveTenantIterator activeTenantIterator;

    public MarketingGroupBuyExpiryTask(
            MarketingApplicationService marketingApplicationService, ActiveTenantIterator activeTenantIterator) {
        this.marketingApplicationService = marketingApplicationService;
        this.activeTenantIterator = activeTenantIterator;
    }

    @Scheduled(fixedDelayString = "${app.marketing.group-buy-expire-delay:PT1M}")
    @SchedulerLock(
            name = "marketing-expire-group-buy-teams",
            lockAtMostFor = "${app.marketing.group-buy-expire-lock-at-most-for:PT10M}")
    public void expireGroupBuyTeams() {
        activeTenantIterator.forEachRetainedTenant(tenantId -> marketingApplicationService.expireGroupBuyTeams());
    }
}

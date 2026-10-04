package com.example.monkey.search.infrastructure;

import com.example.monkey.search.application.SearchApplicationService;
import com.example.monkey.shared.application.tenant.ActiveTenantIterator;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SearchHotKeywordTask {

    private final SearchApplicationService searchApplicationService;
    private final ActiveTenantIterator activeTenantIterator;

    public SearchHotKeywordTask(
            SearchApplicationService searchApplicationService, ActiveTenantIterator activeTenantIterator) {
        this.searchApplicationService = searchApplicationService;
        this.activeTenantIterator = activeTenantIterator;
    }

    @Scheduled(cron = "${app.search.hot-refresh-cron:0 */5 * * * *}")
    @SchedulerLock(name = "search-hot-keyword-refresh", lockAtMostFor = "${app.search.hot-lock-at-most-for:PT1M}")
    public void refreshHotKeywordSnapshot() {
        activeTenantIterator.forEachActiveTenant(tenantId -> {
            searchApplicationService.refreshHotKeywordSnapshot();
            return 0L;
        });
    }
}

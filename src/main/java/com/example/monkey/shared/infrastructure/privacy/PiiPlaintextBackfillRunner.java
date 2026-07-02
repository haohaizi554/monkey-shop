package com.example.monkey.shared.infrastructure.privacy;

import com.example.monkey.shared.infrastructure.privacy.PiiPlaintextBackfillService.BackfillReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.pii.backfill", name = "enabled", havingValue = "true")
public class PiiPlaintextBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PiiPlaintextBackfillRunner.class);

    private final PiiPlaintextBackfillService backfillService;

    public PiiPlaintextBackfillRunner(PiiPlaintextBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        BackfillReport report = backfillService.backfillLegacyPlaintext();
        log.info(
                "PII plaintext backfill completed: users={}, addresses={}, orders={}, total={}",
                report.users(),
                report.addresses(),
                report.orders(),
                report.total());
    }
}

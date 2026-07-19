package com.example.monkey.shared.infrastructure.privacy;

import com.example.monkey.shared.infrastructure.privacy.PiiCiphertextAuditService.AuditReport;
import com.example.monkey.shared.infrastructure.privacy.PiiCiphertextAuditService.FieldAudit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

public final class PiiCiphertextAuditCli {

    private static final Logger log = LoggerFactory.getLogger(PiiCiphertextAuditCli.class);
    private static final String REQUIRE_POPULATED_PROPERTY = "app.pii.ciphertext-audit.require-populated";

    private PiiCiphertextAuditCli() {}

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AuditConfiguration.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(args)) {
            PiiCiphertextAuditService auditService = context.getBean(PiiCiphertextAuditService.class);
            AuditReport report = auditService.auditStoredCiphertext();
            report.fields().forEach(PiiCiphertextAuditCli::logFieldAudit);
            boolean requirePopulated =
                    context.getEnvironment().getProperty(REQUIRE_POPULATED_PROPERTY, Boolean.class, false);
            assertProtected(report, requirePopulated);
            log.info(
                    "Authenticated PII ciphertext audit completed: populated={}, authenticated={}, unprotected={}, blindIndexMismatches={}",
                    report.populatedEncryptedValues(),
                    report.authenticatedCiphertexts(),
                    report.unprotectedValues(),
                    report.blindIndexMismatches());
        }
    }

    static void assertProtected(AuditReport report, boolean requirePopulated) {
        if (requirePopulated && report.populatedEncryptedValues() == 0L) {
            throw new IllegalStateException("PII ciphertext audit found no populated PII values");
        }
        if (!report.protectedAtRest()) {
            throw new IllegalStateException("PII ciphertext audit failed: unprotected="
                    + report.unprotectedValues()
                    + ", blindIndexMismatches="
                    + report.blindIndexMismatches());
        }
    }

    private static void logFieldAudit(String field, FieldAudit audit) {
        log.info(
                "PII ciphertext audit field={}: populated={}, authenticated={}, unprotected={}",
                field,
                audit.populated(),
                audit.authenticated(),
                audit.unprotected());
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        JacksonAutoConfiguration.class
    })
    @Import({PiiKeyMaterialProvider.class, PiiCryptoService.class, PiiCiphertextAuditService.class})
    static class AuditConfiguration {}
}

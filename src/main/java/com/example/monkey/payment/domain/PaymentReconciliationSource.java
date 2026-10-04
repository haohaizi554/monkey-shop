package com.example.monkey.payment.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Supplies one complete, authoritative provider statement for reconciliation.
 *
 * <p>An empty line list is meaningful only when returned successfully by this source: it means the provider
 * attested that the complete statement contains no payments. Implementations must fail instead of returning an
 * empty list when the provider statement is unavailable or incomplete.
 */
@FunctionalInterface
public interface PaymentReconciliationSource {

    Statement fetch(PaymentMethod provider, LocalDate reportDate);

    record Statement(PaymentMethod provider, LocalDate reportDate, List<ReconciliationLine> lines) {

        public Statement {
            provider = Objects.requireNonNull(provider, "provider");
            reportDate = Objects.requireNonNull(reportDate, "reportDate");
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        }
    }
}

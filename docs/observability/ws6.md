# WS6 Observability Drilldown

MonkeyShop now carries `X-Trace-Id` through HTTP responses, JSON logs, async audit writes, and API envelopes. Operators can use a single trace ID to connect the runtime views below.

## TraceId Flow

1. `TraceIdFilter` accepts a valid `X-Trace-Id` or creates a UUID, stores it in MDC as `traceId`, and returns it on the response.
2. `logback-spring.xml` emits JSON logs with `traceId` and `userId`, while masking password, token, cookie, phone, and address fields.
3. `AuditService` copies the current MDC trace ID into `audit_log.trace_id`; `V15__audit_trace_retention.sql` indexes that column.
4. `BusinessMetricsService` publishes order counters, order latency, stock deduction failures, and pending order gauges through Micrometer/Prometheus.
5. OpenTelemetry exports spans through OTLP in production, while Sentry keeps `send-default-pii=false`.

## 99.9% Availability SLO

The Helm chart renders a PrometheusRule-backed availability SLO for staging and production. The default target is `0.999` availability over a `30d` window, which gives a `0.001` HTTP 5xx error budget. `MonkeyShopSloFastBurn` pages on simultaneous 5m and 1h burn above `14.4x`; `MonkeyShopSloSlowBurn` warns on simultaneous 30m and 6h burn above `6x`. The Grafana dashboard exposes `SLO Availability 30d` and `Error Budget Burn Rate` panels so operators can see whether the 99.9% SLA remains on budget.

## Production Drilldown

1. Copy the response `traceId` from the failing request or the frontend error toast.
2. Open the `MonkeyShop Observability` Grafana dashboard and paste it into the `traceId` textbox.
3. Use the dashboard panels for HTTP RPS, p99 latency, 5xx ratio, HikariCP saturation, JVM memory, and business metrics.
4. Use the Loki panel to filter audit-related JSON logs by that trace ID.
5. Use the Tempo panel for span drilldown when the trace was sampled.
6. Use the dashboard `Audit trace API` link, or call `GET /api/stats/audit-trace?traceId=<traceId>` as an administrator with `ADMIN_DASHBOARD_READ`, to fetch the matching sanitized audit rows.

The audit lookup is intentionally capped in the repository query and returns already-sanitized audit details rather than raw identifiers.

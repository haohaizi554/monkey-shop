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

## Dockerless Workstation Stack

The native Windows stack is intentionally separate from the Helm production topology. It gives local acceptance real Collector, Prometheus, Loki, Tempo, and Grafana processes without weakening production defaults.

```powershell
.\scripts\bootstrap-local-observability.ps1 -ProxyUri http://127.0.0.1:7890
.\scripts\start-local.ps1 -WithObservability
.\scripts\verify-local-observability.ps1 -TimeoutSeconds 150
```

Runtime endpoints:

| Service | Endpoint |
| --- | --- |
| OpenTelemetry Collector health | `http://127.0.0.1:13133/` |
| Prometheus | `http://127.0.0.1:9090` |
| Loki | `http://127.0.0.1:3100` |
| Tempo | `http://127.0.0.1:3200` |
| Grafana | `http://127.0.0.1:3000` |

`status-local-observability.ps1` reports health and listener ownership. `stop-local-observability.ps1` stops only identities recorded in `%LOCALAPPDATA%\MonkeyShop\observability\state.json`. Tools, local TSDB/WAL data, logs, and the generated Grafana admin password stay below `%LOCALAPPDATA%\MonkeyShop` and are never committed.

The verifier sends a real Spring request with `traceparent`; it rejects a stack that lacks a server span, exact TraceQL search result, correlated Loki log, Tempo span metric, or provisioned Grafana data source. Tempo also remote-writes `traces_service_graph_request_total` to Prometheus for Grafana Service Map rendering.

# Local Production Support Stack Design

Date: 2026-07-22
Status: approved and in implementation

## Objective

Run every workstation acceptance dependency directly on Windows without Docker, keep all listeners on loopback, preserve data between restarts, and provide repeatable bootstrap, start, stop, status, and verification commands.

## Stage Boundaries

### Batch 10A: Observability

- OpenTelemetry Collector receives OTLP on `4317/4318`, tails MonkeyShop JSON logs, exports traces to Tempo, logs to Loki, and metrics to Prometheus.
- Prometheus scrapes Spring Boot, Collector, Loki, and Tempo on `9090` and accepts Tempo metrics-generator remote writes.
- Tempo stores traces locally on `3200`, emits span metrics and service graph metrics, and retains blocks for 180 days.
- Loki stores logs locally on `3100`, uses a fixed loopback instance address, and retains data for 180 days.
- Grafana runs on `3000` with provisioned Prometheus, Loki, and Tempo data sources plus trace/log/metric links.

### Batch 10B: Security And Storage

- Vault developer service binds to loopback, initializes Transit, and stores generated workstation credentials outside the repository.
- A maintained S3-compatible Windows service supplies the local object-storage contract without using Docker.
- ClamAV runs locally on `3310`, updates signatures through the configured Clash proxy, and gates uploaded content.
- `start-local.ps1` exposes one explicit opt-in mode that wires the application to these services and fails closed when a required dependency is unavailable.

## Security Decisions

- Tool archives are pinned, fetched through `127.0.0.1:7890`, and verified against official SHA-256 manifests before extraction.
- Binaries and runtime state live below `%LOCALAPPDATA%\MonkeyShop`; only declarative config and scripts are committed.
- Services bind to `127.0.0.1`; Grafana anonymous access is read-only and its generated admin password remains outside Git.
- State files record PID, start time, and executable path so stop scripts only terminate processes they own.
- Default `start-local.ps1` behavior remains dependency-light; production-support integrations require explicit switches.

## Acceptance Contract

Batch 10A is complete only when a real Spring HTTP request, propagated with W3C `traceparent`, produces a Tempo server span, searchable TraceQL result, Loki log with the same trace ID, Prometheus span metrics, service graph metrics, and three healthy Grafana data sources. Synthetic traces cannot satisfy this gate.

Batch 10B is complete only when Vault Transit, S3 object upload/read/delete, and ClamAV clean/infected probes pass against native local processes and the application starts with the corresponding integrations enabled.

Public TLS, Turnstile, Sentry SaaS, Sonar, production Kubernetes, signed image admission, and 30-day SLO evidence remain external production proof and are not claimed by this workstation stack.
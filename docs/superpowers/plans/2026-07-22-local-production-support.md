# Local Production Support Implementation Plan

Date: 2026-07-22
Updated: 2026-07-23

## Batch 10A: Native Observability

- [x] Add a pinned, checksum-verified bootstrap through Clash for Collector, Prometheus, Loki, Tempo, and Grafana.
- [x] Add loopback-only configs, persistent local data paths, generated secret handling, and ownership-aware lifecycle scripts.
- [x] Remove the Collector `8888` collision and Loki virtual-adapter self-dial behavior.
- [x] Disable Grafana update/plugin network calls in offline local mode.
- [x] Wire `start-local.ps1 -WithObservability` to OTLP before Spring starts.
- [x] Verify a real Spring server span, exact TraceQL search, correlated Loki log, Prometheus target, span metrics, service graph metrics, and Grafana data sources.
- [x] Prove timed-out application starts terminate their Maven/Java process tree and cannot become untracked late listeners.
- [x] Run repository syntax/config/WS6 gates and push the final Batch 10A checkpoint `3e60d9f1` with green CI/CD, WS1, and CodeQL runs.

## Batch 10B: Native Security And Storage

- [x] Select pinned Windows-capable Vault, S3-compatible storage, and ClamAV distributions with integrity verification.
- [x] Add bootstrap, start, status, stop, and verification scripts with loopback-only listeners and externalized secrets.
- [x] Initialize Vault Transit keys and prove wrap/unwrap operations.
- [x] Create the S3 bucket and prove upload, read, metadata, and delete operations.
- [x] Update ClamAV signatures and prove clean plus EICAR rejection behavior.
- [x] Wire an explicit application startup mode to Vault, S3 storage, and ClamAV; prove healthy startup and fail-closed startup when ClamAV is unavailable.
- [x] Run focused Java contracts and real-service semantic acceptance for Vault, S3, and ClamAV.
- [x] Run the split full repository/runtime gates and update final evidence.
- [x] Restart the pre-fix observability processes and refresh final loopback/runtime evidence.
- [x] Fix cold-bucket provisioning, proxy exclusions, repeated-start mode inheritance, and fail-closed listener checks with red/green regressions.
- [x] Create and push Batch 10B checkpoint `8641d187` with green CI/CD, WS1, and CodeQL runs.

## Runtime Acceptance Hotfix

- [x] Reproduce the live `/api/v1/tracking/events` 502 and trace it to recursively growing encrypted profile summaries.
- [x] Replace recursive history with a bounded latest-event snapshot and add a 100-event regression.
- [x] Pass 15 focused Tracking/JPA/WS11/schema tests, Maven validate, and the full real-browser local runtime gate.
- [x] Push checkpoint `480f4334`; CI/CD `30010693037`, WS1 `30010692977`, and CodeQL `30010693024` all completed successfully.

## Final Delivery

- [x] Run the complete repository/runtime acceptance before Batch 10B, then repeat focused compile, regression, PII, API, rate-limit, and real-browser gates after the tracking hotfix.
- [x] Finish the final hotfix CI run; every local batch is pushed in order with recorded green workflow evidence.
- [x] Record public-edge, SaaS, production key-custody, and real-cluster proof as externally blocked rather than representing missing evidence as complete.
- [ ] Merge the verified remote upgrade branch into `main`, push `main`, and rerun the main-branch acceptance gates.

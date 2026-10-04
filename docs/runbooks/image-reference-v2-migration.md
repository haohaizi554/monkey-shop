# Image-reference v2 Redis protocol migration

This is a one-time maintenance migration. The v2 Redis protocol stops reading
and writing the legacy image-reference hash and rebuilds the authoritative
counts into hash-tagged v2 keys. A v1 Pod that continues writing the legacy hash
therefore creates references that v2 cannot see. The deployment gate is
intentionally fail-closed for that transition; this is not a zero-downtime or
ordinary rolling upgrade.

## Gate contract

`helm/monkeyshop/values-staging.yaml` and `helm/monkeyshop/values-prod.yaml`
set:

```yaml
imageReferenceProtocol:
  migrationMode: true
```

While `migrationMode` is true, the chart must render all of the following:

- an `apps/v1` `Deployment`, even when `rollout.enabled` is true;
- `strategy.type: Recreate`, which terminates every old-protocol Pod before
  creating a new-protocol Pod;
- `revisionHistoryLimit: 0`, so an automatic or simple rollback cannot
  silently restore an old-protocol revision; and
- an HPA whose `scaleTargetRef` is the `apps/v1` `Deployment`.

The Argo `Rollout` and its canary strategy are suppressed during this window.
The normal template path remains available: setting
`imageReferenceProtocol.migrationMode: false` with `rollout.enabled: true`
renders the existing Rollout/canary path.

## Preconditions

Before applying the first migration-mode release:

1. Freeze or otherwise stop writes to the affected image-reference flows.
2. Complete and record a full, restorable database snapshot and a full object
   storage snapshot. A partial snapshot is not a successful precondition.
3. Verify that the Redis v2 protocol is ready for the target release, including
   connectivity, permissions, keyspace configuration, and the protocol's
   read/write smoke check. The protocol keys contain durable deletion claims;
   use a dedicated Redis instance/namespace with `maxmemory-policy noeviction`,
   persistence enabled (AOF with `appendfsync everysec` or a stronger approved
   durability setting), monitored persistence errors, and a tested backup and
   restore procedure. An evicted or non-durable tombstone can make an immutable
   object path reusable after physical deletion.
4. Confirm the Redis topology explicitly. A standalone server or cluster-aware
   proxy uses `SPRING_DATA_REDIS_HOST`/`PORT`; a native Redis Cluster must set
   the comma-separated `SPRING_DATA_REDIS_CLUSTER_NODES` so both Spring Data
   Redis and Redisson use cluster discovery. All v2 Lua data keys use the same
   `{refs}` hash tag and must remain in one slot. A native Cluster deployment
   also requires a real failover and slot-migration acceptance result for that
   environment; the standalone local acceptance test is not sufficient proof.
5. Render staging and production with Helm and confirm that each contains only
   the migration `Deployment` for the application, `Recreate`, revision
   history `0`, and a Deployment-targeted HPA. Run
   `scripts/verify-ws7-devops.ps1 -StaticOnly` only for source checks when Helm
   is unavailable; CI's normal invocation must have Helm and must not silently
   skip rendered checks.
6. Apply the release during the maintenance window and wait for all old Pods to
   terminate before validating the new Pods. Do not manually scale up a second
   application workload with the old image.

Keep `migrationMode: true` until the new protocol has passed readiness,
reference read/write, cleanup, and application smoke checks. Confirm that no
old-protocol process remains able to write the legacy hash.

Do not manually copy the old raw-path or variant fields into the v2 counts hash.
After all v1 writers have drained, run the complete database-reference and
object-storage scan. Only a successful, complete snapshot publication may set
`authoritativeSnapshotReady=1`; a partial database read, object listing, staging
failure, or CAS conflict must leave deletion disabled. Record the v2 metadata,
live-count key persistence (`PTTL=-1`), source counts, object-listing completion,
and smoke-test result as migration evidence. Retain the legacy key read-only
until the rollback window closes, then remove it through an explicit approved
operation rather than from the application hot path.

## Return to canary mode

Only after the first complete DB and storage snapshot is successful and Redis
protocol readiness is proven may the maintenance gate be changed to
`migrationMode: false`. Keep `rollout.enabled: true`, render the chart again,
and confirm that staging and production now contain the Argo Rollout and its
normal canary steps. Treat this as a separate change after the migration has
been validated.

## Rollback restrictions

Do not directly roll back to a v1 image or run a simple Deployment/Argo
rollback. The migration Deployment deliberately renders
`revisionHistoryLimit: 0`, and an old protocol may write data that is
incompatible with v2 or recreate the legacy hash that v2 deletes.

If the migration must be reversed, stop writes first, preserve the migration
evidence, and execute an approved compatible migration plan. That plan must
restore or reconcile the database, object-storage snapshot, and Redis protocol
state as a coordinated operation before deploying a compatible application
revision. Record the restored snapshot and validation results before reopening
writes.

# WS2 Inventory Center

## Goal

Build a multi-warehouse inventory center that moves MonkeyShop beyond single-product stock. Inventory is tracked per SKU and warehouse, with available, locked, deducted, and in-transit quantities protected by distributed locks, optimistic versions, idempotent ledger rows, and scheduled release jobs.

## Scope

- Three warehouses are modeled as Beijing, Shanghai, and Guangzhou inventory nodes.
- Stock is managed at SKU plus warehouse granularity.
- Reserve, release, deduct, and compensate operations keep the invariant:
  `total = available + locked + deducted + in_transit`.
- Reservations expire after 15 minutes and are released by a ShedLock guarded scheduler.
- Ledger rows use Snowflake IDs and an idempotency key to prevent duplicate stock movement.
- Reconciliation compares stock locked/deducted balances with ledger-derived balances and reports drift.

## Migrations

WS1 already consumed `V19` through `V21`, so WS2 continues with:

- `V22__inventory_multi_warehouse.sql`
- `V23__inventory_stock_ledger.sql`

## Acceptance Criteria

- 100 concurrent reserve attempts against 10 available units only reserve 10 units.
- Reservation release is idempotent.
- Deduct moves locked quantity into deducted quantity and cannot oversell.
- Reconciliation detects manual ledger/stock drift.
- Expired reservations are released by a ShedLock scheduled task.
- Inventory write APIs are protected by existing order/product management permissions and rate limiting.

## Invariants

- Domain classes under `com.example.monkey.inventory.domain` have no Spring, JPA, or Hibernate dependencies.
- Every stock movement writes an audit event and an inventory ledger event.
- Every stock movement uses the shared Snowflake `IdGenerator`.
- Every write method is transactional; reads are `readOnly`.
- Redisson locks use the existing `tryLock(2000, 10000, TimeUnit.MILLISECONDS)` contract.

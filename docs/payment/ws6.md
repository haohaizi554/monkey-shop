# WS6 Payment And Reconciliation

## Goal

WS6 turns payment from a demo action into a guarded payment center: users can create
WeChat, Alipay, or bank-card sandbox payments, provider callbacks are signature
checked and idempotent, refunds are written as ledgers, and daily reconciliation
can detect provider/platform drift and suspend risky payment orders.

## Acceptance

- Payment orders use Snowflake IDs and a unique payment number.
- Callback replay is blocked with Redis SETNX and database uniqueness.
- The callback replay path is covered by a unit test and by the WS6 verifier.
- Provider callbacks must carry a valid signature before state changes.
- Amounts over 5000 require a valid user TOTP code.
- Bank-card numbers are stored only as Tink ciphertext plus HMAC blind index.
- The bank-card PII invariant is fail-closed: plaintext card numbers never persist.
- Refunds are original-route, ledger-backed, partial-refund aware, and idempotent.
- Five-minute pending payments are actively queried by ShedLock.
- Reconciliation reports compare provider rows with platform payments, persist an
  encrypted report payload, audit the result, and suspend mismatched payments.
- All state changes go through `PaymentTransitionPolicy` plus optimistic locking.

## Invariants

- `paidAmount >= refundedAmount >= 0`.
- `PENDING` payments can only become `PAID`, `FAILED`, or `SUSPENDED`.
- `PAID` payments can only become `PARTIALLY_REFUNDED`, `REFUNDED`, or `SUSPENDED`.
- A refund request key can create at most one ledger row.
- A provider callback key can be accepted at most once.
- Reconciliation mismatch never silently marks a payment as successful.

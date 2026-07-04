# WS8 Membership And Points Wallet

## Goal

Turn the user profile area into a real membership platform comparable to JD PLUS and Taobao 88VIP: level growth, points wallet, coupon account, collections, price-drop reminders, browsing history, and verified identity PII protection.

## Scope

- Member levels: BASIC, SILVER, GOLD, DIAMOND with growth thresholds and benefit metadata.
- Points wallet: check-in, purchase reward, activity reward, redeem, adjustment, and immutable ledger.
- Coupon account: read the WS3 user coupon wallet as part of the membership dashboard.
- Collections: product favorites with target price and scheduled price-drop reminder.
- Browsing history: Redis-backed 7-day footprint with in-memory fallback for local tests.
- Verified identity: real name and identity number encrypted through the shared PII service and blind indexed.
- Level changes: guarded by TOTP and validated by a Spring StateMachine adapter before optimistic CAS update.

## Invariants

- Every ledger row has a Snowflake id and idempotency key.
- Points balance cannot go below zero.
- One check-in per user per day is enforced by Redis/request idempotency and a database unique key.
- Real name and identity number are never stored as plaintext when PII encryption is enabled.
- Collections are unique by user and product.
- Browsing history expires after 7 days.
- Level changes use StateMachine validation plus versioned CAS.
- Key operations write AuditService events and are traced with OpenTelemetry spans.

## Acceptance

- Daily check-in is idempotent and creates a points ledger row.
- Points changes have a complete immutable ledger and a 100 points = 1 CNY equivalent.
- Price-drop scan creates a reminder event when product price falls below target.
- Browsing history returns the most recent footprints and honors the 7-day TTL contract.
- TOTP is required for membership level changes.
- `scripts/verify-ws8-membership.ps1` and `Ws8MembershipWorkflowTest` pass.

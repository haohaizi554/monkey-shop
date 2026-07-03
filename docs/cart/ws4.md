# WS4 Cart and Split Checkout

## Goal

Build a cross-shop cart and checkout flow that turns selected cart items into shop-level sub-orders while recalculating price, reserving inventory, allocating coupon discounts, and enforcing idempotent checkout.

## Scope

- Store carts as `cart:user:{userId}` Redis hashes with a seven-day TTL.
- Support add, quantity change, select, remove, preview, and checkout operations.
- Split selected items by `shopId` into sub-orders during checkout.
- Recalculate SKU prices at checkout time from the catalog adapter.
- Reserve inventory before checkout persistence by using the WS2 inventory service.
- Calculate coupon discounts by using the WS3 marketing quote engine and allocate discounts to checkout lines.
- Persist checkout master, sub-order, and line snapshots for audit and recovery.
- Use Snowflake IDs for checkout, sub-order, and line records.
- Enforce `Idempotency-Key` for checkout and return the original checkout result on replay.

## Acceptance

- Cross-shop checkout creates one checkout master and one sub-order per shop.
- Price changes after add-to-cart are reflected in preview and checkout.
- Repeated checkout with the same `Idempotency-Key` returns the original checkout.
- Inventory reservation is attempted for every selected SKU and records reservation keys on lines.
- Coupon discounts are allocated across lines without producing negative payable amounts.
- Cart mutation and checkout operations are audited.
- Cart write and checkout endpoints go through API rate limiting.
- Domain classes stay framework-free and ArchUnit protects the cart layer boundary.

## Invariants

- A cart line is uniquely identified by `userId + skuId`.
- Quantity must be between 1 and 999.
- Checkout cannot run with zero selected lines.
- Checkout is immutable once persisted.
- `sum(line.payableAmount) == checkout.payableAmount`.
- `line.originalAmount == line.unitPrice * line.quantity`.
- Selected cart lines are removed only after checkout persistence succeeds.
- Existing unrelated YAML, CSP, and diagram changes are intentionally not part of this WS.

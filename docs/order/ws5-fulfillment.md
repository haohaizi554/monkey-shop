# WS5 Order Fulfillment

## Goal

Extend MonkeyShop orders from a single shipment demo into a fulfillment workflow that supports partial shipment, shipment-level receipt, automatic receipt after seven days, return/refund state linkage, and product reviews with image references.

## Scope

- Shipment batches split an order by SKU/product line and carrier tracking number.
- Fulfillment items track ordered, shipped, and received quantities so partial shipment and partial receipt can be derived by CAS-protected state transitions.
- Reviews require a completed order, one review per user/order/SKU, rating 1-5, optional anonymous display, and image paths retained through the shared image reference service.
- Auto receipt is a ShedLock guarded scheduled task that receives shipped batches older than the configured window.
- Existing order return/refund endpoints remain state-machine driven and continue to restore stock idempotently.

## Acceptance

- Shipping fewer than all ordered quantities moves the order to `PARTIALLY_SHIPPED`; shipping all quantities moves it to `SHIPPED`.
- Receiving fewer than all shipped quantities moves the order to `PARTIALLY_RECEIVED`; receiving all quantities moves it to `COMPLETED`.
- Auto receipt is idempotent and guarded by `@SchedulerLock`.
- A completed order can be reviewed once per SKU by its owner, with image references retained.
- The workflow is covered by `Ws5FulfillmentWorkflowTest` and `scripts/verify-ws5-fulfillment.ps1`.

## Invariants

- Quantity counters never exceed ordered quantity.
- Order status changes go through `OrderTransitionResolver` and `transitionStatus` CAS.
- Shipment IDs, shipment line IDs, fulfillment item IDs, and review IDs use the shared Snowflake `IdGenerator`.
- Key operations write audit events.

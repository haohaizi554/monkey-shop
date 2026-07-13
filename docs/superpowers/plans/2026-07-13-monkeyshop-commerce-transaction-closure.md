# MonkeyShop Commerce Transaction Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn cart checkout into a real, idempotent order/payment/inventory/coupon/refund/logistics/risk/event flow with no financial-loss shortcuts.

**Architecture:** Use explicit application ports between modules and an outbox-backed event pipeline. The cart owns preview and cart mutation; the order module owns formal order creation and state; payment, inventory, marketing, logistics, membership, tracking, and risk react through idempotent application services or persisted business events.

**Tech Stack:** Java 21, Spring Boot transactions, Spring Data JPA, MySQL 8, Redis/Redisson, Flyway, JUnit 5, Mockito, Testcontainers or locally provisioned integration profiles.

## Global Constraints

- Cart preview is read-only; successful checkout atomically creates formal orders before clearing the cart.
- Platform coupons apply once across all shops; store coupons apply only to their owning shop.
- New orders start `PENDING_PAYMENT`; no creation path may start `PAID`.
- Same idempotency key with a different normalized request fingerprint returns HTTP 409.
- A single order has at most one active payment intent.
- Refunds originate from a legal return/admin approval state.
- Production profiles must reject sandbox payment and logistics adapters.
- Risk blocks normal checkout, seckill, group-buy, and payment at server-side decision points.
- All projections are idempotent and consume server-created events, never client-crafted conversion events.

---

## File Map

- `cart/application/CartApplicationService.java`: orchestrates checkout, but delegates formal order creation.
- `order/application/CheckoutOrderApplicationService.java`: new transaction owner for formal checkout orders.
- `order/domain/CheckoutOrderCommand.java`: immutable cross-module command with money allocation snapshots.
- `marketing/application/MarketingApplicationService.java`: ownership, quote, atomic redemption, compensation.
- `payment/application/PaymentApplicationService.java`: intent/refund fingerprints and provider truth.
- `shared/application/events`: persisted outbox event types and handlers.
- `logistics/application/LogisticsApplicationService.java`: fulfillment-only shipment creation and receipt synchronization.

### Task 1: Prove The Current Checkout Gap With A Failing Integration Test

**Files:**
- Create: `src/test/java/com/example/monkey/commerce/CheckoutTransactionIntegrationTest.java`
- Create: `src/test/resources/application-test.yml`

**Interfaces:**
- Consumes: `CartApplicationService.checkout(SessionUser, String, CartCheckoutRequestDto)`.
- Verifies: cart checkout ID maps to persisted formal orders with `PENDING_PAYMENT`, lines, allocation totals, and inventory reservations.

- [ ] **Step 1: Write the RED test**

```java
CartCheckoutResponseDto checkout = cartService.checkout(user, request, "checkout-key-1");

List<OrderResponseDto> orders = orderService.findOrdersForUser(user.userId());
assertThat(orders).hasSize(2).allSatisfy(order ->
        assertThat(order.status()).isEqualTo("PENDING_PAYMENT"));
assertThat(orders).extracting(OrderResponseDto::checkoutId)
        .containsOnly(checkout.id());
assertThat(cartService.cart(user).items()).isEmpty();
```

- [ ] **Step 2: Run the test and confirm RED**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=CheckoutTransactionIntegrationTest' test`

Expected: checkout rows exist but `orders` is empty.

- [ ] **Step 3: Commit only the executable failing test**

```powershell
git add src/test/java/com/example/monkey/commerce/CheckoutTransactionIntegrationTest.java src/test/resources/application-test.yml
git commit -m "test(commerce): expose cart order persistence gap"
```

### Task 2: Create Formal Pending-Payment Orders From Checkout

**Files:**
- Create: `src/main/java/com/example/monkey/order/domain/CheckoutOrderCommand.java`
- Create: `src/main/java/com/example/monkey/order/application/CheckoutOrderApplicationService.java`
- Create: `src/main/java/com/example/monkey/cart/domain/FormalOrderCreator.java`
- Create: `src/main/java/com/example/monkey/order/infrastructure/OrderFormalOrderCreator.java`
- Modify: `src/main/java/com/example/monkey/cart/application/CartApplicationService.java`
- Modify: `src/main/java/com/example/monkey/cart/application/CartDtoAssembler.java`
- Modify: `src/main/java/com/example/monkey/cart/application/dto/CartCheckoutResponseDto.java`
- Modify: `src/main/java/com/example/monkey/cart/application/dto/CartSubOrderResponseDto.java`
- Modify: `src/main/java/com/example/monkey/cart/domain/CheckoutOrder.java`
- Modify: `src/main/java/com/example/monkey/cart/domain/CheckoutSubOrder.java`
- Modify: `src/main/java/com/example/monkey/order/domain/OrderStatus.java`
- Modify: `src/main/java/com/example/monkey/order/application/OrderDtoAssembler.java`
- Modify: `src/main/java/com/example/monkey/order/application/dto/OrderResponseDto.java`
- Modify: `src/main/java/com/example/monkey/order/infrastructure/Order.java`
- Create: `src/main/java/com/example/monkey/order/infrastructure/OrderLineEntity.java`
- Create: `src/main/resources/db/migration/V49__link_checkout_to_orders.sql`
- Test: `src/test/java/com/example/monkey/order/application/CheckoutOrderApplicationServiceTest.java`
- Test: `src/test/java/com/example/monkey/commerce/CheckoutTransactionIntegrationTest.java`

**Interfaces:**
- Produces: `List<Long> FormalOrderCreator.create(CheckoutOrderCommand command)`.
- `CheckoutOrderCommand` contains `checkoutId`, `userId`, `addressId`, `idempotencyKey`, and `List<SubOrder>`; each suborder contains shop ID, subtotal, store discount, platform allocation, payable total, and immutable line snapshots.

- [ ] **Step 1: Add RED unit assertions for amount conservation**

```java
List<Long> orderIds = service.create(command);
assertThat(orderIds).hasSize(2);
assertThat(orderStore.findByCheckoutId(command.checkoutId()))
        .allSatisfy(order -> assertThat(order.status()).isEqualTo(PENDING_PAYMENT));
assertThat(sumPayable(orderStore.findByCheckoutId(command.checkoutId())))
        .isEqualByComparingTo(command.totalPayable());
```

- [ ] **Step 2: Run RED unit test**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=CheckoutOrderApplicationServiceTest' test`

Expected: the new service and command do not exist.

- [ ] **Step 3: Implement transaction ordering**

```java
private CheckoutOrder checkoutLocked(Long userId, CartCheckoutRequestDto request, String key) {
    Optional<CheckoutOrder> existing = checkoutStore.findByUserIdAndIdempotencyKey(userId, key);
    if (existing.isPresent()) return existing.get();
    CheckoutOrder checkout = buildCheckout(userId, request, key, true);
    List<Long> orderIds = formalOrderCreator.create(toCommand(checkout, request.addressId(), key));
    marketing.redeemForCheckout(userId, checkout.id(), checkout.appliedCoupons());
    CheckoutOrder saved = checkoutStore.save(checkout.confirmed(orderIds));
    cartStore.removeItems(userId, checkout.selectedSkuIds(), cartTtl);
    return saved;
}
```

Every call is within the same local transaction. Persist order, order line, suborder allocation, and checkout linkage. Remove or redirect the legacy single-product `OrderService.createOrder` so its initial state is also `PENDING_PAYMENT`.

- [ ] **Step 4: Run unit + integration GREEN and migration validation**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=CheckoutOrderApplicationServiceTest,CheckoutTransactionIntegrationTest,SchemaMigrationTest' test`

Expected: all tests pass and Flyway validates V49.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/example/monkey/order/domain/CheckoutOrderCommand.java src/main/java/com/example/monkey/order/application/CheckoutOrderApplicationService.java src/main/java/com/example/monkey/cart/domain/FormalOrderCreator.java src/main/java/com/example/monkey/order/infrastructure/OrderFormalOrderCreator.java src/main/java/com/example/monkey/cart/application/CartApplicationService.java src/main/java/com/example/monkey/cart/application/CartDtoAssembler.java src/main/java/com/example/monkey/cart/application/dto/CartCheckoutResponseDto.java src/main/java/com/example/monkey/cart/application/dto/CartSubOrderResponseDto.java src/main/java/com/example/monkey/cart/domain/CheckoutOrder.java src/main/java/com/example/monkey/cart/domain/CheckoutSubOrder.java src/main/java/com/example/monkey/order/domain/OrderStatus.java src/main/java/com/example/monkey/order/application/OrderDtoAssembler.java src/main/java/com/example/monkey/order/application/dto/OrderResponseDto.java src/main/java/com/example/monkey/order/infrastructure/Order.java src/main/java/com/example/monkey/order/infrastructure/OrderLineEntity.java src/main/resources/db/migration/V49__link_checkout_to_orders.sql src/test/java/com/example/monkey/order/application/CheckoutOrderApplicationServiceTest.java src/test/java/com/example/monkey/commerce/CheckoutTransactionIntegrationTest.java
git commit -m "feat(order): persist checkout as pending payment orders"
```

### Task 3: Enforce Coupon Ownership And Atomic Redemption

**Files:**
- Modify: `src/main/java/com/example/monkey/marketing/application/MarketingApplicationService.java`
- Modify: `src/main/java/com/example/monkey/marketing/domain/MarketingStore.java`
- Modify: `src/main/java/com/example/monkey/marketing/infrastructure/JpaMarketingStore.java`
- Modify: `src/main/java/com/example/monkey/marketing/infrastructure/MarketingUserCouponEntity.java`
- Modify: `src/main/java/com/example/monkey/marketing/interfaces/MarketingController.java`
- Create: `src/main/resources/db/migration/V50__bind_coupon_redemption_to_checkout.sql`
- Test: `src/test/java/com/example/monkey/marketing/application/MarketingApplicationServiceTest.java`
- Test: `src/test/java/com/example/monkey/commerce/CheckoutTransactionIntegrationTest.java`

**Interfaces:**
- Produces: `CouponQuote quoteForUser(long userId, CouponScope scope, List<PriceLine> lines, List<String> codes)`.
- Produces: `void redeemForCheckout(long userId, long checkoutId, List<AppliedCoupon> coupons)`.
- Produces: `void returnForCheckout(long userId, long checkoutId, String reason)` with idempotent compare-and-set semantics.

- [ ] **Step 1: Write RED ownership and allocation tests**

```java
assertThatThrownBy(() -> service.quoteForUser(otherUserId, PLATFORM, lines, List.of("PLATFORM-20")))
        .isInstanceOf(ResourceOwnershipException.class);

CartCheckoutResponseDto result = checkoutTwoShopsWithPlatformCoupon();
assertThat(result.discountAmount()).isEqualByComparingTo("20.00");
assertThat(result.subOrders().stream().map(CartSubOrderResponseDto::platformDiscountAmount).reduce(ZERO, BigDecimal::add))
        .isEqualByComparingTo("20.00");
```

- [ ] **Step 2: Run RED tests**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=MarketingApplicationServiceTest,CheckoutTransactionIntegrationTest,CartApplicationServiceTest' test`

Expected: unbound quote/redeem or missing checkout binding fails.

- [ ] **Step 3: Implement guarded state transitions**

Use one `UPDATE marketing_user_coupon SET status='REDEEMED', checkout_id=:checkoutId WHERE tenant_id=:tenantId AND user_id=:userId AND code=:code AND status='CLAIMED' AND valid_from<=:now AND valid_to>:now`. Require update count 1; return uses checkout ID and only transitions `REDEEMED -> CLAIMED` once.

- [ ] **Step 4: Run GREEN and commit**

```powershell
git add src/main/java/com/example/monkey/marketing/application/MarketingApplicationService.java src/main/java/com/example/monkey/marketing/domain/MarketingStore.java src/main/java/com/example/monkey/marketing/infrastructure/JpaMarketingStore.java src/main/java/com/example/monkey/marketing/infrastructure/MarketingUserCouponEntity.java src/main/java/com/example/monkey/marketing/interfaces/MarketingController.java src/main/resources/db/migration/V50__bind_coupon_redemption_to_checkout.sql src/test/java/com/example/monkey/marketing/application/MarketingApplicationServiceTest.java src/test/java/com/example/monkey/commerce/CheckoutTransactionIntegrationTest.java
git commit -m "fix(marketing): bind coupon redemption to owned checkout"
```

### Task 4: Bind Payment And Refund Idempotency To Request Fingerprints

**Files:**
- Create: `src/main/java/com/example/monkey/payment/domain/PaymentRequestFingerprint.java`
- Modify: `src/main/java/com/example/monkey/payment/domain/PaymentStore.java`
- Modify: `src/main/java/com/example/monkey/payment/infrastructure/JpaPaymentStore.java`
- Modify: `src/main/java/com/example/monkey/payment/infrastructure/PaymentOrderEntity.java`
- Modify: `src/main/java/com/example/monkey/payment/infrastructure/PaymentLedgerEntity.java`
- Modify: `src/main/java/com/example/monkey/payment/application/PaymentApplicationService.java`
- Create: `src/main/resources/db/migration/V51__payment_request_fingerprints.sql`
- Test: `src/test/java/com/example/monkey/payment/application/PaymentApplicationServiceTest.java`
- Test: `src/test/java/com/example/monkey/payment/infrastructure/JpaPaymentStoreTest.java`

**Interfaces:**
- Produces: `PaymentRequestFingerprint.of(orderId, method, amount, currency)` and `ofRefund(paymentId, amount, normalizedReason)` using canonical JSON + SHA-256.
- Enforces: idempotency key reuse with equal fingerprint returns the prior response; unequal fingerprint throws conflict.
- Enforces: unique active payment intent per `(tenant_id, order_id)` for active statuses.

- [ ] **Step 1: Write RED collision tests**

```java
PaymentResponseDto first = service.createPayment(user, orderId, "same-key", WECHAT);
assertThat(service.createPayment(user, orderId, "same-key", WECHAT)).isEqualTo(first);
assertThatThrownBy(() -> service.createPayment(user, otherOrderId, "same-key", WECHAT))
        .isInstanceOf(IdempotencyConflictException.class);
```

- [ ] **Step 2: Run RED tests**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=PaymentApplicationServiceTest,JpaPaymentStoreTest' test`

Expected: the existing lookup returns a prior record solely by user/key.

- [ ] **Step 3: Persist and compare fingerprints**

```java
PaymentIntent existing = store.findByUserAndIdempotencyKey(userId, key).orElse(null);
if (existing != null && !existing.requestFingerprint().equals(fingerprint.value())) {
    throw new IdempotencyConflictException("幂等键已用于不同支付请求");
}
```

Normalize currency as `CNY`, money to scale 2, enum names to uppercase, and refund reason by Unicode trim + internal whitespace collapse before hashing.

- [ ] **Step 4: Run GREEN, migration test, and commit**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=PaymentApplicationServiceTest,JpaPaymentStoreTest,SchemaMigrationTest' test`

```powershell
git add src/main/java/com/example/monkey/payment/domain/PaymentRequestFingerprint.java src/main/java/com/example/monkey/payment/domain/PaymentStore.java src/main/java/com/example/monkey/payment/infrastructure/JpaPaymentStore.java src/main/java/com/example/monkey/payment/infrastructure/PaymentOrderEntity.java src/main/java/com/example/monkey/payment/infrastructure/PaymentLedgerEntity.java src/main/java/com/example/monkey/payment/application/PaymentApplicationService.java src/main/resources/db/migration/V51__payment_request_fingerprints.sql src/test/java/com/example/monkey/payment/application/PaymentApplicationServiceTest.java src/test/java/com/example/monkey/payment/infrastructure/JpaPaymentStoreTest.java
git commit -m "fix(payment): bind idempotency to request fingerprints"
```

### Task 5: Publish Durable Payment Success Events And Project Side Effects

**Files:**
- Create: `src/main/java/com/example/monkey/shared/domain/event/BusinessEvent.java`
- Create: `src/main/java/com/example/monkey/shared/domain/event/PaymentSucceededEvent.java`
- Create: `src/main/java/com/example/monkey/shared/application/event/OutboxPublisher.java`
- Create: `src/main/java/com/example/monkey/shared/infrastructure/event/OutboxEventEntity.java`
- Create: `src/main/java/com/example/monkey/shared/infrastructure/event/OutboxEventRepository.java`
- Create: `src/main/java/com/example/monkey/shared/infrastructure/event/JpaOutboxPublisher.java`
- Create: `src/main/java/com/example/monkey/payment/application/PaymentSuccessProjector.java`
- Modify: `src/main/java/com/example/monkey/payment/application/PaymentApplicationService.java`
- Create: `src/main/resources/db/migration/V52__business_event_outbox.sql`
- Test: `src/test/java/com/example/monkey/payment/application/PaymentSuccessProjectorTest.java`
- Test: `src/test/java/com/example/monkey/commerce/PaymentSuccessIntegrationTest.java`

**Interfaces:**
- Produces: `PaymentSucceededEvent(eventId, tenantId, paymentId, orderId, userId, paidAmount, occurredAt)` saved in the same transaction as payment success.
- Produces: idempotent projections keyed by `eventId` for order transition, inventory confirmation, fulfillment readiness, membership points, tracking conversion, and audit.

- [ ] **Step 1: Write RED integration assertions**

```java
providerCallbackSuccess(payment);
assertThat(orderStore.get(orderId).status()).isEqualTo(PAID);
assertThat(inventoryStore.findReservationByOrder(orderId).status()).isEqualTo(CONFIRMED);
assertThat(pointsStore.findLedgerBySource("PAYMENT", payment.id())).isPresent();
assertThat(trackingStore.findConversionByEventId(eventId)).isPresent();
```

- [ ] **Step 2: Run RED tests**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=PaymentSuccessProjectorTest,PaymentSuccessIntegrationTest' test`

Expected: payment succeeds but dependent modules remain unchanged.

- [ ] **Step 3: Implement outbox persistence and idempotent projection**

```java
@Transactional
public void project(PaymentSucceededEvent event) {
    if (!projectionStore.claim(event.eventId(), "payment-success")) return;
    orderPayments.markPaid(event.orderId(), event.paymentId(), event.paidAmount());
    inventoryPayments.confirm(event.orderId());
    fulfillment.prepare(event.orderId());
    membershipPayments.award(event.userId(), event.orderId(), event.paidAmount());
    trackingPayments.record(event);
}
```

Persist each projection claim with a unique `(event_id, projector)` constraint. Retry failed outbox records; do not mark dispatched until every local projector succeeds.

- [ ] **Step 4: Run GREEN and commit**

```powershell
git add src/main/java/com/example/monkey/shared/domain/event src/main/java/com/example/monkey/shared/application/event src/main/java/com/example/monkey/shared/infrastructure/event src/main/java/com/example/monkey/payment/application/PaymentSuccessProjector.java src/main/java/com/example/monkey/payment/application/PaymentApplicationService.java src/main/resources/db/migration/V52__business_event_outbox.sql src/test/java/com/example/monkey/payment/application/PaymentSuccessProjectorTest.java src/test/java/com/example/monkey/commerce/PaymentSuccessIntegrationTest.java
git commit -m "feat(commerce): project durable payment success events"
```

### Task 6: Gate Refunds Through Returns And Fix Reconciliation Input

**Files:**
- Modify: `src/main/java/com/example/monkey/order/application/OrderService.java`
- Modify: `src/main/java/com/example/monkey/payment/application/PaymentApplicationService.java`
- Create: `src/main/java/com/example/monkey/payment/domain/ProviderReconciliationSource.java`
- Create: `src/main/java/com/example/monkey/payment/infrastructure/ConfiguredProviderReconciliationSource.java`
- Modify: `src/main/java/com/example/monkey/payment/interfaces/PaymentAdminController.java`
- Test: `src/test/java/com/example/monkey/payment/application/PaymentApplicationServiceTest.java`
- Test: `src/test/java/com/example/monkey/payment/Ws6PaymentWorkflowTest.java`
- Test: `src/test/java/com/example/monkey/order/application/OrderServiceTest.java`

**Interfaces:**
- Produces: `refund(SessionUser admin, long orderId, String key, Money amount, String reason)` only when order is `RETURNING`/`RETURN_APPROVED` or an audited admin override is present.
- Produces: `List<ProviderReconciliationLine> fetch(PaymentMethod method, LocalDate date)`; scheduled reconciliation never supplies an empty hard-coded list.

- [ ] **Step 1: Write RED tests for legal state and provider rows**

```java
assertThatThrownBy(() -> service.refund(admin, paidOrderId, "r1", amount, "buyer asked"))
        .isInstanceOf(IllegalOrderTransitionException.class);

when(providerSource.fetch(WECHAT, yesterday)).thenReturn(List.of(matchingProviderLine));
assertThat(service.reconcileYesterday().status()).isEqualTo(MATCHED);
verify(providerSource).fetch(WECHAT, yesterday);
```

- [ ] **Step 2: Run RED tests**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=PaymentApplicationServiceTest,Ws6PaymentWorkflowTest,OrderServiceTest' test`

Expected: direct refund is accepted or scheduled reconciliation calls `List.of()`.

- [ ] **Step 3: Implement legal refund entry and provider source**

The scheduled method calls `providerSource.fetch(method, yesterday)`. If the source is unavailable, record `SOURCE_UNAVAILABLE`, emit an alert metric, and do not suspend valid payments. Manual reconciliation accepts uploaded provider lines only under `PAYMENT_RECONCILE` permission and audit.

- [ ] **Step 4: Run GREEN and commit**

```powershell
git add src/main/java/com/example/monkey/order/application/OrderService.java src/main/java/com/example/monkey/payment/application/PaymentApplicationService.java src/main/java/com/example/monkey/payment/domain/ProviderReconciliationSource.java src/main/java/com/example/monkey/payment/infrastructure/ConfiguredProviderReconciliationSource.java src/main/java/com/example/monkey/payment/interfaces/PaymentAdminController.java src/test/java/com/example/monkey/payment/application/PaymentApplicationServiceTest.java src/test/java/com/example/monkey/payment/Ws6PaymentWorkflowTest.java src/test/java/com/example/monkey/order/application/OrderServiceTest.java
git commit -m "fix(payment): reconcile provider truth and gate refunds"
```

### Task 7: Integrate Risk At Every Commercial Decision Point

**Files:**
- Create: `src/main/java/com/example/monkey/risk/domain/CommercialRiskGate.java`
- Create: `src/main/java/com/example/monkey/risk/infrastructure/RiskApplicationGate.java`
- Modify: `src/main/java/com/example/monkey/cart/application/CartApplicationService.java`
- Modify: `src/main/java/com/example/monkey/marketing/application/MarketingApplicationService.java`
- Modify: `src/main/java/com/example/monkey/payment/application/PaymentApplicationService.java`
- Test: `src/test/java/com/example/monkey/risk/CommercialRiskGateIntegrationTest.java`
- Test: `src/test/java/com/example/monkey/marketing/application/MarketingApplicationServiceTest.java`

**Interfaces:**
- Produces: `void requireAllowed(RiskContext context)` for actions `CHECKOUT`, `SECKILL`, `GROUP_BUY`, and `PAYMENT`.
- Maps: `ALLOW` continues, `REVIEW` returns HTTP 409 with review reference, `RATE_LIMIT` returns 429 + retry, `DENY` returns 403.

- [ ] **Step 1: Write RED decision tests**

```java
when(riskGate.assess(contextFor(SECKILL))).thenReturn(RiskDecision.RATE_LIMIT);
assertThatThrownBy(() -> marketing.seckill(request, userId))
        .isInstanceOf(RateLimitExceededException.class);
verify(marketingStore, never()).saveSeckillOrder(any());
```

- [ ] **Step 2: Run RED tests**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=CommercialRiskGateIntegrationTest,MarketingApplicationServiceTest,PaymentApplicationServiceTest,CartApplicationServiceTest' test`

Expected: one or more business paths do not invoke risk.

- [ ] **Step 3: Inject the port before side effects**

```java
riskGate.requireAllowed(RiskContext.forCheckout(
        currentUser.tenantId(), currentUser.userId(), checkout.totalPayable(), clientSignals));
```

Call it before inventory reservation, coupon redemption, seckill stock decrement, group member persistence, or payment intent persistence.

- [ ] **Step 4: Run GREEN and commit**

```powershell
git add src/main/java/com/example/monkey/risk/domain/CommercialRiskGate.java src/main/java/com/example/monkey/risk/infrastructure/RiskApplicationGate.java src/main/java/com/example/monkey/cart/application/CartApplicationService.java src/main/java/com/example/monkey/marketing/application/MarketingApplicationService.java src/main/java/com/example/monkey/payment/application/PaymentApplicationService.java src/test/java/com/example/monkey/risk/CommercialRiskGateIntegrationTest.java src/test/java/com/example/monkey/marketing/application/MarketingApplicationServiceTest.java
git commit -m "fix(risk): enforce commercial decision gates"
```

### Task 8: Restrict Shipment Creation And Synchronize Receipt

**Files:**
- Modify: `src/main/java/com/example/monkey/logistics/interfaces/LogisticsController.java`
- Modify: `src/main/java/com/example/monkey/logistics/application/LogisticsApplicationService.java`
- Create: `src/main/java/com/example/monkey/logistics/domain/OrderFulfillmentPort.java`
- Create: `src/main/java/com/example/monkey/order/infrastructure/LogisticsOrderFulfillmentAdapter.java`
- Modify: `src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java`
- Test: `src/test/java/com/example/monkey/logistics/interfaces/LogisticsControllerTest.java`
- Test: `src/test/java/com/example/monkey/logistics/application/LogisticsApplicationServiceTest.java`
- Test: `src/test/java/com/example/monkey/commerce/FulfillmentIntegrationTest.java`

**Interfaces:**
- Produces: shipment creation requires `ORDER_FULFILL` or `ORDER_MANAGE` and order state `PAID`/`PARTIALLY_SHIPPED`.
- Produces: verified delivered webhook transitions shipment and order receipt idempotently.

- [ ] **Step 1: Write RED authorization and state tests**

```java
mockMvc.perform(post("/api/v1/logistics/shipments").with(user(consumer)))
        .andExpect(status().isForbidden());
assertThatThrownBy(() -> service.createShipment(operator, pendingPaymentOrderRequest))
        .isInstanceOf(IllegalOrderTransitionException.class);
```

- [ ] **Step 2: Run RED tests**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=LogisticsControllerTest,LogisticsApplicationServiceTest,FulfillmentIntegrationTest' test`

- [ ] **Step 3: Add fulfillment port and receipt synchronization**

```java
public interface OrderFulfillmentPort {
    void requireShippable(long tenantId, long orderId);
    void markShipmentCreated(long tenantId, long orderId, long shipmentId);
    void markDelivered(long tenantId, long orderId, long shipmentId, Instant deliveredAt);
}
```

Only a signature-verified webhook can call `markDelivered`; replayed provider event IDs return the prior result.

- [ ] **Step 4: Run GREEN and commit**

```powershell
git add src/main/java/com/example/monkey/logistics/interfaces/LogisticsController.java src/main/java/com/example/monkey/logistics/application/LogisticsApplicationService.java src/main/java/com/example/monkey/logistics/domain/OrderFulfillmentPort.java src/main/java/com/example/monkey/order/infrastructure/LogisticsOrderFulfillmentAdapter.java src/main/java/com/example/monkey/shared/infrastructure/config/SecurityConfig.java src/test/java/com/example/monkey/logistics/interfaces/LogisticsControllerTest.java src/test/java/com/example/monkey/logistics/application/LogisticsApplicationServiceTest.java src/test/java/com/example/monkey/commerce/FulfillmentIntegrationTest.java
git commit -m "fix(logistics): secure shipment lifecycle"
```

### Task 9: Reject Sandbox Providers In Production

**Files:**
- Modify: `src/main/java/com/example/monkey/payment/infrastructure/SandboxPaymentGateway.java`
- Modify: `src/main/java/com/example/monkey/logistics/infrastructure/SandboxLogisticsGateway.java`
- Create: `src/main/java/com/example/monkey/shared/infrastructure/config/ProviderModeGuard.java`
- Modify: `src/main/resources/application-dev.yml`
- Modify: `src/main/resources/application-prod.yml`
- Test: `src/test/java/com/example/monkey/shared/infrastructure/config/ProviderModeGuardTest.java`
- Create: `src/test/java/com/example/monkey/payment/infrastructure/SandboxPaymentGatewayTest.java`

**Interfaces:**
- Produces: dev/test may set `monkeyshop.providers.mode=sandbox`; prod requires `payment` and `logistics` adapters with mode `live` and nonblank provider endpoints/credentials.

- [ ] **Step 1: Write RED profile startup test**

```java
assertThatThrownBy(() -> runner.withPropertyValues(
        "spring.profiles.active=prod", "monkeyshop.providers.mode=sandbox").run())
        .hasRootCauseMessage("Sandbox payment/logistics providers are forbidden in prod");
```

- [ ] **Step 2: Run RED, implement guard, run GREEN**

Run: `& 'D:\APP\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd' -B '-Ddependency-check.skip=true' '-Dtest=ProviderModeGuardTest,SandboxPaymentGatewayTest,SandboxLogisticsGatewayTest' test`

Expected after implementation: tests pass; production context refuses sandbox mode.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/example/monkey/payment/infrastructure/SandboxPaymentGateway.java src/main/java/com/example/monkey/logistics/infrastructure/SandboxLogisticsGateway.java src/main/java/com/example/monkey/shared/infrastructure/config/ProviderModeGuard.java src/main/resources/application-dev.yml src/main/resources/application-prod.yml src/test/java/com/example/monkey/shared/infrastructure/config/ProviderModeGuardTest.java
git commit -m "fix(platform): forbid sandbox providers in production"
```

## Plan Acceptance

- The real cart checkout integration test persists pending-payment orders and clears only selected cart lines after success.
- Coupon ownership, validity, scope, one-time platform allocation, atomic redemption, and idempotent return are covered.
- Payment/refund idempotency conflicts return 409; only one active payment intent exists per order.
- Payment success updates order, inventory, fulfillment preparation, points, trusted tracking, and audit exactly once.
- Reconciliation fetches real configured provider rows or reports source unavailable without suspending valid orders.
- Refund, shipment, webhook, and risk transitions reject invalid callers/states before side effects.
- Production context cannot start with sandbox payment or logistics adapters.

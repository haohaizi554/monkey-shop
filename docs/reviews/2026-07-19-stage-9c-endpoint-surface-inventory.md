# Stage 9C Endpoint Surface Inventory

Generated from controller annotations. Canonical endpoints: 122.

- CONSUMER_UI: 66
- ADMIN_UI: 43
- MACHINE_ONLY: 2
- SCHEDULED_INTERNAL: 0
- API_ONLY: 11

| Method | Canonical path | Surface | Handler | Authorization | Evidence |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/v1/addresses` | CONSUMER_UI | `AddressController#myAddresses` | `hasAuthority('ADDRESS_MANAGE')` | `frontend/src/api/user.ts` -> `/profile` |
| POST | `/api/v1/addresses` | CONSUMER_UI | `AddressController#addAddress` | `hasAuthority('ADDRESS_MANAGE')` | `frontend/src/api/user.ts` -> `/profile` |
| POST | `/api/v1/addresses/set-default/{id}` | CONSUMER_UI | `AddressController#setDefault` | `hasAuthority('ADDRESS_MANAGE')` | `frontend/src/api/user.ts` -> `/profile` |
| DELETE | `/api/v1/addresses/{id}` | CONSUMER_UI | `AddressController#delete` | `hasAuthority('ADDRESS_MANAGE')` | `frontend/src/api/user.ts` -> `/profile` |
| PUT | `/api/v1/addresses/{id}` | CONSUMER_UI | `AddressController#update` | `hasAuthority('ADDRESS_MANAGE')` | `frontend/src/api/user.ts` -> `/profile` |
| GET | `/api/v1/auth/captcha` | CONSUMER_UI | `AuthController#getCaptcha` | `permitAll()` | `frontend/src/api/auth.ts` -> `/login` |
| GET | `/api/v1/auth/captcha/config` | CONSUMER_UI | `AuthController#getCaptchaConfig` | `permitAll()` | `frontend/src/api/auth.ts` -> `/login` |
| POST | `/api/v1/auth/login` | CONSUMER_UI | `AuthController#login` | `permitAll()` | `frontend/src/api/auth.ts` -> `/login` |
| GET | `/api/v1/auth/password-policy` | CONSUMER_UI | `AuthController#passwordPolicy` | `permitAll()` | `frontend/src/api/auth.ts` -> `/login` |
| POST | `/api/v1/auth/refresh` | CONSUMER_UI | `AuthController#refresh` | `permitAll()` | `frontend/src/api/auth.ts` -> `/login` |
| POST | `/api/v1/auth/register` | CONSUMER_UI | `AuthController#register` | `permitAll()` | `frontend/src/api/auth.ts` -> `/login` |
| POST | `/api/v1/auth/reset-password` | CONSUMER_UI | `AuthController#resetPassword` | `permitAll()` | `frontend/src/api/auth.ts` -> `/login` |
| POST | `/api/v1/auth/reset-password/request` | CONSUMER_UI | `AuthController#requestPasswordReset` | `permitAll()` | `frontend/src/api/auth.ts` -> `/login` |
| GET | `/api/v1/cart` | CONSUMER_UI | `CartController#cart` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/cart.ts` -> `/cart` and `/checkout` |
| POST | `/api/v1/cart/checkout` | CONSUMER_UI | `CartController#checkout` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/cart.ts` -> `/cart` and `/checkout` |
| POST | `/api/v1/cart/checkout/preview` | CONSUMER_UI | `CartController#previewCheckout` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/cart.ts` -> `/cart` and `/checkout` |
| POST | `/api/v1/cart/items` | CONSUMER_UI | `CartController#addItem` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/cart.ts` -> `/cart` and `/checkout` |
| DELETE | `/api/v1/cart/items/{skuId}` | CONSUMER_UI | `CartController#removeItem` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/cart.ts` -> `/cart` and `/checkout` |
| PATCH | `/api/v1/cart/items/{skuId}` | CONSUMER_UI | `CartController#updateItem` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/cart.ts` -> `/cart` and `/checkout` |
| POST | `/api/v1/cart/items/{skuId}/select` | CONSUMER_UI | `CartController#selectItem` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/cart.ts` -> `/cart` and `/checkout` |
| GET | `/api/v1/catalog/categories` | CONSUMER_UI | `CatalogController#categoryTree` | `permitAll()` | `frontend/src/api/catalog.ts` -> `/shop` and product workflows |
| GET | `/api/v1/catalog/categories/tree` | CONSUMER_UI | `CatalogController#categoryTree` | `permitAll()` | `frontend/src/api/catalog.ts` -> `/shop` and product workflows |
| POST | `/api/v1/catalog/spus` | API_ONLY | `CatalogController#createSpu` | `hasAuthority('PRODUCT_MANAGE')` | versioned catalog write retained for external catalog integrations |
| GET | `/api/v1/catalog/spus/{spuId}` | CONSUMER_UI | `CatalogController#getSpu` | `permitAll()` | `frontend/src/api/catalog.ts` -> `/shop` and product workflows |
| GET | `/api/v1/catalog/spus/{spuId}/price` | CONSUMER_UI | `CatalogController#quotePrice` | `permitAll()` | `frontend/src/api/catalog.ts` -> `/shop` and product workflows |
| POST | `/api/v1/catalog/spus/{spuId}/status` | API_ONLY | `CatalogController#transitionStatus` | `hasAuthority('PRODUCT_MANAGE')` | versioned catalog lifecycle retained for external catalog integrations |
| POST | `/api/v1/inventory/compensations` | API_ONLY | `InventoryController#compensate` | `hasAuthority('ORDER_MANAGE')` | order-orchestration compensation; no direct browser control |
| GET | `/api/v1/inventory/reconciliation` | ADMIN_UI | `InventoryController#reconcile` | `hasAuthority('ORDER_MANAGE')` | `frontend/src/api/inventory.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/inventory/reservations` | ADMIN_UI | `InventoryController#reserve` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/inventory.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/inventory/reservations/{reservationKey}/deduct` | API_ONLY | `InventoryController#deduct` | `hasAuthority('ORDER_MANAGE')` | order-orchestration deduction; no direct browser control |
| POST | `/api/v1/inventory/reservations/{reservationKey}/release` | ADMIN_UI | `InventoryController#release` | `hasAuthority('ORDER_MANAGE')` | `frontend/src/api/inventory.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/inventory/skus/{skuId}/stocks` | CONSUMER_UI | `InventoryController#stocks` | `hasAnyAuthority('ORDER_CREATE', 'ORDER_MANAGE', 'PRODUCT_MANAGE')` | `frontend/src/api/inventory.ts` -> `/shop` and product workflows |
| POST | `/api/v1/logistics/address/parse` | CONSUMER_UI | `LogisticsController#parseAddress` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/logistics.ts` -> `/logistics/:orderId` |
| POST | `/api/v1/logistics/freight/quote` | CONSUMER_UI | `LogisticsController#quoteFreight` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/logistics.ts` -> `/logistics/:orderId` |
| GET | `/api/v1/logistics/orders/{orderId}` | CONSUMER_UI | `LogisticsController#findByOrder` | `hasAuthority('ORDER_READ_OWN')` | `frontend/src/api/logistics.ts` -> `/logistics/:orderId` |
| POST | `/api/v1/logistics/shipments` | API_ONLY | `LogisticsController#createShipment` | `hasAuthority('ORDER_READ_OWN')` | legacy owner shipment contract retained without a browser client |
| GET | `/api/v1/logistics/tracking/{trackingNo}` | CONSUMER_UI | `LogisticsController#findByTrackingNo` | `hasAuthority('ORDER_READ_OWN')` | `frontend/src/api/logistics.ts` -> `/logistics/:orderId` |
| POST | `/api/v1/logistics/webhook` | MACHINE_ONLY | `LogisticsController#webhook` | `permitAll()` | signed carrier webhook; server boundary only |
| POST | `/api/v1/marketing/coupons/claim` | ADMIN_UI | `MarketingController#claimCoupon` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/marketing.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/marketing/coupons/redeem` | ADMIN_UI | `MarketingController#redeemCoupon` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/marketing.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/marketing/coupons/return` | ADMIN_UI | `MarketingController#returnCoupon` | `hasAnyAuthority('ORDER_CREATE', 'ORDER_MANAGE')` | `frontend/src/api/marketing.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/marketing/group-buy/join` | ADMIN_UI | `MarketingController#joinGroupBuy` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/marketing.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/marketing/price/quote` | ADMIN_UI | `MarketingController#quotePrice` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/marketing.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/marketing/seckill-orders` | ADMIN_UI | `MarketingController#createSeckillOrder` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/marketing.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/membership/admin/{userId}/dashboard` | ADMIN_UI | `MembershipController#adminDashboard` | `hasAuthority('MEMBERSHIP_ADMIN')` | `frontend/src/api/membership.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/membership/admin/{userId}/level` | ADMIN_UI | `MembershipController#adminChangeLevel` | `hasAuthority('MEMBERSHIP_ADMIN')` | `frontend/src/api/membership.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/membership/admin/{userId}/points/earn` | ADMIN_UI | `MembershipController#adminEarnPoints` | `hasAuthority('MEMBERSHIP_ADMIN')` | `frontend/src/api/membership.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/membership/browse` | CONSUMER_UI | `MembershipController#recordBrowse` | `hasAuthority('MEMBERSHIP_WRITE')` | `frontend/src/api/membership.ts` -> `/membership` |
| POST | `/api/v1/membership/check-in` | CONSUMER_UI | `MembershipController#checkIn` | `hasAuthority('MEMBERSHIP_WRITE')` | `frontend/src/api/membership.ts` -> `/membership` |
| POST | `/api/v1/membership/collections` | CONSUMER_UI | `MembershipController#addCollection` | `hasAuthority('MEMBERSHIP_WRITE')` | `frontend/src/api/membership.ts` -> `/membership` |
| DELETE | `/api/v1/membership/collections/{productId}` | CONSUMER_UI | `MembershipController#removeCollection` | `hasAuthority('MEMBERSHIP_WRITE')` | `frontend/src/api/membership.ts` -> `/membership` |
| GET | `/api/v1/membership/dashboard` | CONSUMER_UI | `MembershipController#dashboard` | `hasAuthority('MEMBERSHIP_READ')` | `frontend/src/api/membership.ts` -> `/membership` |
| POST | `/api/v1/membership/identity` | CONSUMER_UI | `MembershipController#verifyIdentity` | `hasAuthority('MEMBERSHIP_WRITE')` | `frontend/src/api/membership.ts` -> `/membership` |
| POST | `/api/v1/membership/level` | API_ONLY | `MembershipController#changeLevel` | `hasAuthority('MEMBERSHIP_ADMIN')` | legacy self-targeting admin contract retained for compatibility |
| POST | `/api/v1/membership/points/earn` | API_ONLY | `MembershipController#earnPoints` | `hasAuthority('MEMBERSHIP_ADMIN')` | legacy self-targeting admin contract retained for compatibility |
| POST | `/api/v1/membership/points/redeem` | CONSUMER_UI | `MembershipController#redeemPoints` | `hasAuthority('MEMBERSHIP_WRITE')` | `frontend/src/api/membership.ts` -> `/membership` |
| POST | `/api/v1/membership/price-drops/scan` | ADMIN_UI | `MembershipController#scanPriceDrops` | `hasAuthority('MEMBERSHIP_ADMIN')` | `frontend/src/api/membership.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/monkeys` | CONSUMER_UI | `MonkeyController#getMonkeys` | `permitAll()` | `frontend/src/api/catalog.ts` -> `/shop` and product workflows |
| POST | `/api/v1/monkeys/add` | ADMIN_UI | `MonkeyController#addMonkey` | `hasAuthority('PRODUCT_MANAGE')` | `frontend/src/api/catalog.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/monkeys/update` | ADMIN_UI | `MonkeyController#updateMonkey` | `hasAuthority('PRODUCT_MANAGE')` | `frontend/src/api/catalog.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| DELETE | `/api/v1/monkeys/{id}` | ADMIN_UI | `MonkeyController#deleteMonkey` | `hasAuthority('PRODUCT_MANAGE')` | `frontend/src/api/catalog.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/orders/admin/{id}/shipments` | ADMIN_UI | `OrderController#adminShipments` | `hasAuthority('ORDER_MANAGE')` | `frontend/src/api/orders.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/orders/all` | ADMIN_UI | `OrderController#getAllOrders` | `hasAuthority('ORDER_MANAGE')` | `frontend/src/api/orders.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/orders/create` | CONSUMER_UI | `OrderController#createOrder` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/orders.ts` -> `/orders` and order detail workflows |
| GET | `/api/v1/orders/my` | CONSUMER_UI | `OrderController#myOrders` | `hasAuthority('ORDER_READ_OWN')` | `frontend/src/api/orders.ts` -> `/orders` and order detail workflows |
| POST | `/api/v1/orders/receive/{id}` | CONSUMER_UI | `OrderController#receiveOrder` | `hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)` | `frontend/src/api/orders.ts` -> `/orders` and order detail workflows |
| POST | `/api/v1/orders/return/apply/{id}` | CONSUMER_UI | `OrderController#applyReturn` | `hasAuthority('ORDER_RETURN_REQUEST') and @orderOwnership.isOwner(#id, authentication)` | `frontend/src/api/orders.ts` -> `/orders` and order detail workflows |
| POST | `/api/v1/orders/return/approve/{id}` | ADMIN_UI | `OrderController#approveReturn` | `hasAuthority('ORDER_MANAGE')` | `frontend/src/api/orders.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/orders/return/confirm/{id}` | ADMIN_UI | `OrderController#confirmReturn` | `hasAuthority('ORDER_MANAGE')` | `frontend/src/api/orders.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/orders/return/ship/{id}` | CONSUMER_UI | `OrderController#userShipReturn` | `hasAuthority('ORDER_RETURN_REQUEST') and @orderOwnership.isOwner(#id, authentication)` | `frontend/src/api/orders.ts` -> `/orders` and order detail workflows |
| GET | `/api/v1/orders/review/{id}` | CONSUMER_UI | `OrderController#reviews` | `hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)` | `frontend/src/api/orders.ts` -> `/orders` and order detail workflows |
| POST | `/api/v1/orders/review/{id}` | CONSUMER_UI | `OrderController#reviewOrder` | `hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)` | `frontend/src/api/orders.ts` -> `/orders` and order detail workflows |
| POST | `/api/v1/orders/ship/{id}` | ADMIN_UI | `OrderController#shipOrder` | `hasAuthority('ORDER_MANAGE')` | `frontend/src/api/orders.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/orders/shipments/receive/{id}` | CONSUMER_UI | `OrderController#receiveShipment` | `hasAuthority('ORDER_READ_OWN')` | `frontend/src/api/orders.ts` -> `/orders` and order detail workflows |
| POST | `/api/v1/orders/shipments/{id}` | ADMIN_UI | `OrderController#shipOrder` | `hasAuthority('ORDER_MANAGE')` | `frontend/src/api/orders.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| DELETE | `/api/v1/orders/{id}` | CONSUMER_UI | `OrderController#hideOrder` | `hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)` | `frontend/src/api/orders.ts` -> `/orders` and order detail workflows |
| GET | `/api/v1/orders/{id}/shipments` | CONSUMER_UI | `OrderController#shipments` | `hasAuthority('ORDER_READ_OWN') and @orderOwnership.isOwner(#id, authentication)` | `frontend/src/api/orders.ts` -> `/orders` and order detail workflows |
| GET | `/api/v1/payments/admin/orders/{orderId}` | ADMIN_UI | `PaymentAdminController#findByOrder` | `hasAuthority('ORDER_MANAGE')` | `frontend/src/api/payments.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/payments/admin/refund` | ADMIN_UI | `PaymentAdminController#refund` | `hasAuthority('ORDER_MANAGE')` | `frontend/src/api/payments.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/payments/callback` | MACHINE_ONLY | `PaymentController#callback` | `permitAll()` | signed payment-provider callback; server boundary only |
| GET | `/api/v1/payments/orders/{orderId}` | CONSUMER_UI | `PaymentController#findByOrder` | `hasAuthority('ORDER_READ_OWN')` | `frontend/src/api/payments.ts` -> `/payment/:orderId` and return workflows |
| POST | `/api/v1/payments/pay` | CONSUMER_UI | `PaymentController#createPayment` | `hasAuthority('ORDER_CREATE')` | `frontend/src/api/payments.ts` -> `/payment/:orderId` and return workflows |
| POST | `/api/v1/payments/reconciliation` | ADMIN_UI | `PaymentController#reconcile` | `hasAuthority('ORDER_MANAGE')` | `frontend/src/api/payments.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/payments/refund` | CONSUMER_UI | `PaymentController#refund` | `hasAuthority('ORDER_READ_OWN')` | `frontend/src/api/payments.ts` -> `/payment/:orderId` and return workflows |
| POST | `/api/v1/risk/assess` | CONSUMER_UI | `RiskController#assess` | `hasAuthority('RISK_WRITE')` | `frontend/src/api/risk.ts` -> `/shop` and product workflows |
| GET | `/api/v1/risk/reviews` | ADMIN_UI | `RiskController#reviewQueue` | `hasAuthority('RISK_REVIEW')` | `frontend/src/api/risk.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/risk/reviews/{caseId}/resolve` | ADMIN_UI | `RiskController#resolveReview` | `hasAuthority('RISK_REVIEW')` | `frontend/src/api/risk.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/search/conversions` | CONSUMER_UI | `SearchController#recordConversion` | `hasAuthority('SEARCH_WRITE')` | `frontend/src/api/search.ts` -> `/search` and `/recommendations` |
| GET | `/api/v1/search/hot` | CONSUMER_UI | `SearchController#hotKeywords` | `permitAll()` | `frontend/src/api/search.ts` -> `/search` and `/recommendations` |
| GET | `/api/v1/search/products` | CONSUMER_UI | `SearchController#products` | `permitAll()` | `frontend/src/api/search.ts` -> `/search` and `/recommendations` |
| POST | `/api/v1/search/profile` | CONSUMER_UI | `SearchController#upsertProfile` | `hasAuthority('SEARCH_WRITE')` | `frontend/src/api/search.ts` -> `/search` and `/recommendations` |
| GET | `/api/v1/search/recommendations` | CONSUMER_UI | `SearchController#recommendations` | `hasAuthority('SEARCH_READ')` | `frontend/src/api/search.ts` -> `/search` and `/recommendations` |
| GET | `/api/v1/search/suggestions` | CONSUMER_UI | `SearchController#suggestions` | `permitAll()` | `frontend/src/api/search.ts` -> `/search` and `/recommendations` |
| GET | `/api/v1/stats/audit-trace` | ADMIN_UI | `StatsController#getAuditTrace` | `hasAuthority('ADMIN_DASHBOARD_READ')` | `frontend/src/api/admin.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/stats/data` | ADMIN_UI | `StatsController#getStats` | `hasAuthority('ADMIN_DASHBOARD_READ')` | `frontend/src/api/admin.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/tenants` | ADMIN_UI | `TenantAdminController#tenants` | `hasAuthority('TENANT_READ')` | `frontend/src/api/tenant.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/tenants` | ADMIN_UI | `TenantAdminController#createTenant` | `hasAuthority('TENANT_ADMIN')` | `frontend/src/api/tenant.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/tenants/dashboard` | ADMIN_UI | `TenantAdminController#dashboard` | `hasAuthority('TENANT_ADMIN')` | `frontend/src/api/tenant.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/tenants/{tenantId}/bills` | ADMIN_UI | `TenantAdminController#bills` | `hasAuthority('TENANT_READ')` | `frontend/src/api/tenant.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/tenants/{tenantId}/bills` | ADMIN_UI | `TenantAdminController#generateBill` | `hasAuthority('TENANT_ADMIN')` | `frontend/src/api/tenant.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/tenants/{tenantId}/configs` | ADMIN_UI | `TenantAdminController#configs` | `hasAuthority('TENANT_READ')` | `frontend/src/api/tenant.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| PUT | `/api/v1/tenants/{tenantId}/configs` | ADMIN_UI | `TenantAdminController#upsertConfig` | `hasAuthority('TENANT_ADMIN')` | `frontend/src/api/tenant.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/tenants/{tenantId}/downgrade` | ADMIN_UI | `TenantAdminController#downgradeTenant` | `hasAuthority('TENANT_ADMIN')` | `frontend/src/api/tenant.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/tenants/{tenantId}/exports` | ADMIN_UI | `TenantAdminController#exports` | `hasAuthority('TENANT_READ')` | `frontend/src/api/tenant.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/tenants/{tenantId}/exports` | ADMIN_UI | `TenantAdminController#requestExport` | `hasAuthority('TENANT_ADMIN')` | `frontend/src/api/tenant.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/tenants/{tenantId}/renew` | ADMIN_UI | `TenantAdminController#renewTenant` | `hasAuthority('TENANT_ADMIN')` | `frontend/src/api/tenant.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/tracking/dashboard` | ADMIN_UI | `TrackingController#dashboard` | `hasAuthority('TRACKING_ADMIN')` | `frontend/src/api/tracking.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| POST | `/api/v1/tracking/events` | CONSUMER_UI | `TrackingController#recordEvent` | `permitAll()` | `frontend/src/api/tracking.ts` -> shared page tracking |
| GET | `/api/v1/tracking/funnel` | API_ONLY | `TrackingController#funnel` | `hasAuthority('TRACKING_ADMIN')` | analytics integration endpoint; dashboard uses the aggregate response |
| GET | `/api/v1/tracking/products/{productId}` | ADMIN_UI | `TrackingController#productProfile` | `hasAuthority('TRACKING_ADMIN')` | `frontend/src/api/tracking.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/tracking/profile/me` | ADMIN_UI | `TrackingController#currentUserProfile` | `hasAuthority('TRACKING_READ')` | `frontend/src/api/tracking.ts` -> `/admin`, `/inventory`, `/marketing`, `/risk`, `/dashboard`, or `/tenants` |
| GET | `/api/v1/tracking/profile/{userId}` | API_ONLY | `TrackingController#userProfile` | `hasAuthority('TRACKING_ADMIN')` | support integration endpoint; no arbitrary-user browser lookup |
| POST | `/api/v1/uploads` | CONSUMER_UI | `UploadController#upload` | `hasAuthority('UPLOAD_PRODUCT_IMAGE') or (#request.type() == 'avatar' and hasAuthority('UPLOAD_AVATAR'))` | `frontend/src/api/catalog.ts` -> `/profile` |
| POST | `/api/v1/uploads/avatar` | API_ONLY | `UploadController#uploadAvatar` | `hasAuthority('UPLOAD_AVATAR')` | typed upload compatibility endpoint; UI uses the guarded generic upload |
| POST | `/api/v1/uploads/product` | API_ONLY | `UploadController#uploadProduct` | `hasAuthority('UPLOAD_PRODUCT_IMAGE')` | typed upload compatibility endpoint; UI uses the guarded generic upload |
| GET | `/api/v1/users/captcha` | CONSUMER_UI | `UserController#getCaptcha` | `hasAuthority('USER_PROFILE_WRITE')` | `frontend/src/api/user.ts` -> `/profile` |
| POST | `/api/v1/users/forget-me` | CONSUMER_UI | `PrivacyController#forgetMe` | `hasAuthority('USER_PROFILE_WRITE')` | `frontend/src/api/user.ts` -> `/profile` |
| POST | `/api/v1/users/logout` | CONSUMER_UI | `SecurityFilterChain#logout` | `authenticated + CSRF` | `frontend/src/api/auth.ts` -> consumer and admin shell sign-out controls |
| GET | `/api/v1/users/me` | CONSUMER_UI | `UserController#getCurrentUser` | `permitAll()` | `frontend/src/api/user.ts` -> `/profile` |
| GET | `/api/v1/users/profile` | CONSUMER_UI | `UserController#getProfile` | `hasAuthority('USER_PROFILE_READ')` | `frontend/src/api/user.ts` -> `/profile` |
| POST | `/api/v1/users/update-avatar` | CONSUMER_UI | `UserController#updateAvatar` | `hasAuthority('USER_PROFILE_WRITE')` | `frontend/src/api/user.ts` -> `/profile` |
| POST | `/api/v1/users/update-password` | CONSUMER_UI | `UserController#updatePassword` | `hasAuthority('USER_PROFILE_WRITE')` | `frontend/src/api/user.ts` -> `/profile` |

export type Role = 'ADMIN' | 'USER' | string

export interface ApiResult<T> {
  code: string
  message: string
  data: T
  traceId?: string
}

export interface ProblemDetail {
  title?: string
  detail?: string
  status?: number
  code?: string
  traceId?: string
}

export interface UserProfile {
  isLogin?: boolean
  identity?: Role
  username?: string
  avatar?: string
  maskedPhone?: string
  passwordChangeRequired?: boolean
}

export interface AvatarUpdateRequest {
  avatarPath: string
}

export interface LoginRequest {
  username: string
  password: string
  captcha?: string
  totp?: string
}

export interface LoginResponse {
  role: Role
  passwordChangeRequired: boolean
}

export interface CaptchaConfig {
  provider: 'local' | 'turnstile' | string
  siteKey?: string
}

export interface RegisterRequest {
  username: string
  password: string
  phone: string
  email?: string
  captcha: string
  avatarFile?: File | null
}

export interface PasswordResetChallenge {
  username: string
  phone: string
  email?: string
  captcha?: string
}

export interface PasswordResetRequest extends PasswordResetChallenge {
  otp: string
  emailToken?: string
  newPassword: string
}

export interface Monkey {
  id: number
  name: string
  breed: string
  price: string | number
  description?: string
  imageUrl: string
  stock: number
  categoryId?: number
  categoryName?: string
  status?: ProductStatus
  memberPrice?: string | number
  strikePrice?: string | number
  regionPrices?: Record<string, string | number>
  attributes?: Record<string, unknown>
  detailJsonLd?: string
  skus?: CatalogSku[]
  selectedSkuId?: number
}

export interface MonkeyRequest {
  id?: number | null
  name: string
  breed: string
  price: string | number
  description?: string
  imageUrl: string
  stock: number
}

export interface Address {
  id: number
  receiverName: string
  phone: string
  detailAddress: string
  isDefault: number
}

export type AddressRequest = Pick<Address, 'receiverName' | 'phone' | 'detailAddress'>

export interface Order {
  id: number
  orderNo: string
  userId: number
  buyerName: string
  buyerAvatar?: string
  productId: number
  productName: string
  productImage: string
  price: string | number
  description?: string
  receiverName: string
  receiverPhone: string
  addressSnapshot: string
  shippingTime?: string
  status: string
  createTime: string
}

export interface OrderShipmentLine {
  skuId: number
  productName: string
  quantity: number
}

export interface OrderShipment {
  id: number
  orderId: number
  shipmentNo: string
  carrier: string
  trackingNo: string
  status: 'SHIPPED' | 'RECEIVED'
  shippedAt: string
  receivedAt?: string
  lines: OrderShipmentLine[]
}

export interface OrderShipmentLineRequest {
  skuId: number
  productName?: string
  quantity: number
  orderedQuantity: number
}

export interface OrderShipmentRequest {
  carrier?: string
  trackingNo?: string
  lines: OrderShipmentLineRequest[]
}

export interface OrderReviewRequest {
  skuId?: number
  rating: number
  content?: string
  imageUrls: string[]
  anonymous: boolean
}

export interface OrderReview {
  id: number
  orderId: number
  userId: number
  skuId: number
  rating: number
  content?: string
  imageUrls: string[]
  anonymous: boolean
  createTime: string
}

export type PaymentMethod = 'WECHAT' | 'ALIPAY' | 'BANK_CARD'

export type PaymentStatus =
  'PENDING' | 'PAID' | 'PARTIALLY_REFUNDED' | 'REFUNDED' | 'SUSPENDED' | 'FAILED'

export interface PaymentCreateRequest {
  orderId: number
  method: PaymentMethod
  bankCardNo?: string
  totpCode?: string
}

export interface PaymentResponse {
  id: number
  paymentNo: string
  orderId: number
  userId: number
  method: PaymentMethod
  amount: string | number
  paidAmount: string | number
  refundedAmount: string | number
  status: PaymentStatus
  providerTradeNo?: string
  bankCardLast4?: string
  paymentUrl?: string
  paidAt?: string
  createTime: string
}

export interface PaymentRefundRequest {
  paymentNo: string
  amount: string | number
  reason?: string
}

export interface PaymentRefundResponse {
  ledgerId: number
  paymentNo: string
  amount: string | number
  refundedAmount: string | number
  paymentStatus: PaymentStatus
  ledgerStatus: 'SUCCESS' | 'ACCEPTED' | 'FAILED'
  createTime: string
}

export interface ReconciliationLine {
  paymentNo: string
  providerTradeNo?: string
  amount: string | number
}

export interface PaymentReconciliationRequest {
  provider: PaymentMethod
  reportDate: string
  lines: ReconciliationLine[]
}

export interface PaymentReconciliationResponse {
  id: number
  provider: PaymentMethod
  reportDate: string
  platformAmount: string | number
  providerAmount: string | number
  diffAmount: string | number
  issueCount: number
  status: 'BALANCED' | 'DIFF' | 'SUSPENDED'
  createTime: string
}

export type LogisticsCarrier = 'SF' | 'ZTO' | 'YTO'
export type TrackingStatus = 'ORDERED' | 'PICKED_UP' | 'IN_TRANSIT' | 'OUT_FOR_DELIVERY' | 'SIGNED'
export type TrackingEvent = 'PICKUP' | 'TRANSIT' | 'DISPATCH' | 'SIGN'
export type FreightChargeMode = 'WEIGHT' | 'ITEM' | 'REGION'

export interface ShipmentCreateRequest {
  orderId: number
  carrier: LogisticsCarrier
  recipientPhone?: string
  addressText?: string
  province?: string
  city?: string
  district?: string
  detail?: string
  weightKg: string | number
  itemCount: number
}

export interface FreightQuoteRequest {
  carrier: LogisticsCarrier
  province?: string
  weightKg: string | number
  itemCount: number
}

export interface FreightQuoteResponse {
  carrier: LogisticsCarrier
  province?: string
  weightKg: string | number
  itemCount: number
  amount: string | number
  etaHours: number
  appliedModes: FreightChargeMode[]
}

export interface ParsedAddress {
  province: string
  city: string
  district: string
  detail: string
}

export interface AddressParseRequest {
  text: string
}

export interface TrackingWebhookRequest {
  carrier: LogisticsCarrier
  trackingNo: string
  eventId: string
  event: TrackingEvent
  eventTime?: string
  location?: string
  remark?: string
  signature: string
}

export interface TrackingEventRecord {
  id: number
  eventType: TrackingEvent
  fromStatus: TrackingStatus
  toStatus: TrackingStatus
  eventId: string
  eventTime: string
  location?: string
  remark?: string
}

export interface LogisticsTracking {
  id: number
  trackingNo: string
  orderId: number
  userId: number
  carrier: LogisticsCarrier
  status: TrackingStatus
  province?: string
  city?: string
  district?: string
  detailSummary?: string
  freightAmount: string | number
  etaHours: number
  pickedUpAt?: string
  inTransitAt?: string
  outForDeliveryAt?: string
  signedAt?: string
  createTime: string
  updateTime: string
  events: TrackingEventRecord[]
}

export interface Stats {
  totalGmv: string
  totalOrders: number
  totalVisits: number
  returnRate: string
  xAxis: string[]
  seriesOrder: number[]
  seriesGmv: Array<string | number>
  seriesVisit: number[]
}

export interface UploadResponse {
  path: string
  cropped: boolean
  variants: Record<string, string>
}

export type ProductStatus =
  'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'LISTED' | 'UNLISTED' | 'RECYCLED'

export interface CatalogSku {
  id: number
  spuId: number
  skuCode: string
  specification: Record<string, string>
  originalPrice: string | number
  memberPrice?: string | number
  strikePrice?: string | number
  regionPrices: Record<string, string | number>
  active: boolean
}

export interface CatalogSpu {
  id: number
  categoryId: number
  name: string
  title: string
  status: ProductStatus
  originalPrice: string | number
  memberPrice?: string | number
  strikePrice?: string | number
  regionPrices: Record<string, string | number>
  attributes: Record<string, unknown>
  detailJsonLd?: string
  imageUrl?: string
  skus: CatalogSku[]
}

export interface CatalogPriceQuote {
  spuId: number
  salePrice: string | number
  strikePrice?: string | number
  strategy: string
}

export interface CategoryNode {
  id: number
  parentId?: number | null
  level: number
  code: string
  name: string
  children: CategoryNode[]
}

export interface WarehouseStock {
  skuId: number
  warehouseId: number
  warehouseCode?: string
  province?: string
  availableQuantity: number
  lockedQuantity: number
  deductedQuantity: number
  inTransitQuantity: number
  safetyStock: number
  totalQuantity: number
  belowSafetyStock: boolean
}

export interface InventoryReserveRequest {
  skuId: number
  warehouseId?: number
  province?: string
  orderId?: number
  quantity: number
  reservationKey: string
}

export interface InventoryCompensateRequest {
  skuId: number
  warehouseId: number
  orderId?: number
  quantity: number
  idempotencyKey: string
}

export interface InventoryReservation {
  reservationKey: string
  skuId: number
  warehouseId: number
  orderId?: number
  quantity: number
  status: 'RESERVED' | 'RELEASED' | 'DEDUCTED' | 'EXPIRED'
  expiresAt: string
  stock: WarehouseStock
}

export interface InventoryReconciliation {
  balanced: boolean
  discrepancies: InventoryDiscrepancy[]
}

export interface InventoryDiscrepancy {
  skuId: number
  warehouseId: number
  actualLocked: number
  expectedLocked: number
  actualDeducted: number
  expectedDeducted: number
}

export interface CouponClaimRequest {
  couponId: number
  userId: number
  idempotencyKey: string
}

export interface CouponRedeemRequest {
  couponCode: string
  orderId: number
}

export interface CouponReturnRequest {
  couponCode: string
  orderId: number
}

export interface CouponWalletEntry {
  id: number
  couponId: number
  couponCode: string
  userId: number
  status: 'CLAIMED' | 'USED' | 'RETURNED' | 'EXPIRED'
  orderId?: number
  claimedAt: string
  usedAt?: string
}

export interface MarketingPriceRequest {
  orderAmount: string | number
  userId?: number
  categoryId?: number
  shopId?: number
  couponCodes: string[]
}

export interface MarketingPriceQuote {
  originalAmount: string | number
  discountAmount: string | number
  payableAmount: string | number
  appliedCoupons: string[]
}

export interface SeckillRequest {
  activityId: number
  userId: number
  orderId?: number
  quantity: number
  idempotencyKey: string
  turnstileToken?: string
}

export interface SeckillOrder {
  id: number
  activityId: number
  skuId: number
  userId: number
  orderId?: number
  quantity: number
  idempotencyKey: string
  createdAt: string
}

export interface GroupBuyJoinRequest {
  activityId: number
  userId: number
  teamId?: number
  idempotencyKey: string
}

export interface GroupBuyTeam {
  id: number
  activityId: number
  skuId: number
  leaderUserId: number
  targetSize: number
  joinedCount: number
  status: 'OPEN' | 'SUCCEEDED' | 'CANCELLED'
  expiresAt: string
}

export interface CartAddItemRequest {
  skuId: number
  shopId: number
  quantity: number
  selected: boolean
}

export interface CartUpdateItemRequest {
  quantity: number
}

export interface CartSelectItemRequest {
  selected: boolean
}

export interface CartItem {
  skuId: number
  shopId: number
  productName: string
  productImage?: string
  unitPrice: string | number
  quantity: number
  selected: boolean
  lineAmount: string | number
  updatedAt: string
}

export interface Cart {
  userId: number
  items: CartItem[]
  selectedQuantity: number
  selectedAmount: string | number
}

export interface CartCheckoutRequest {
  addressId: number
  province?: string
  couponCodes: string[]
}

export interface CartCheckoutLine {
  id: number
  skuId: number
  shopId: number
  categoryId?: number
  productName: string
  productImage?: string
  quantity: number
  unitPrice: string | number
  originalAmount: string | number
  discountAmount: string | number
  payableAmount: string | number
  couponCodes: string[]
  reservationKey: string
  warehouseId?: number
}

export interface CartSubOrder {
  id: number
  shopId: number
  orderNo: string
  originalAmount: string | number
  discountAmount: string | number
  payableAmount: string | number
  status: 'RESERVED' | 'CHECKED_OUT'
  lines: CartCheckoutLine[]
}

export interface CartCheckout {
  id: number
  checkoutNo: string
  userId: number
  addressId: number
  originalAmount: string | number
  discountAmount: string | number
  payableAmount: string | number
  status: 'RESERVED' | 'CHECKED_OUT'
  province?: string
  createdAt: string
  subOrders: CartSubOrder[]
}

export type MembershipLevel = 'BASIC' | 'SILVER' | 'GOLD' | 'DIAMOND'

export interface MemberProfile {
  userId: number
  level: MembershipLevel
  growthValue: number
  verified: boolean
  maskedRealName?: string
  maskedIdCardNo?: string
  version: number
  benefits: string[]
}

export interface PointsWallet {
  userId: number
  balance: number
  totalEarned: number
  totalSpent: number
  moneyEquivalent: string | number
  version: number
}

export interface MembershipCouponWalletEntry {
  id: number
  couponId: number
  couponCode: string
  status: 'CLAIMED' | 'USED' | 'RETURNED' | 'EXPIRED'
  orderId?: number
  claimedAt: string
  usedAt?: string
}

export interface MemberCollection {
  id: number
  productId: number
  productName: string
  productImage?: string
  lastPrice: string | number
  targetPrice?: string | number
  priceDropNotified: boolean
  createTime: string
  updateTime: string
}

export interface BrowseHistoryEntry {
  productId: number
  productName: string
  productImage?: string
  viewedAt: string
  expiresAt: string
}

export interface MembershipDashboard {
  profile: MemberProfile
  wallet: PointsWallet
  coupons: MembershipCouponWalletEntry[]
  collections: MemberCollection[]
  browseHistory: BrowseHistoryEntry[]
}

export interface RealNameVerifyRequest {
  realName: string
  idCardNo: string
}

export interface PointsEarnRequest {
  orderId?: number
  amount: string | number
  referenceKey?: string
}

export interface PointsRedeemRequest {
  points: number
  referenceKey?: string
}

export interface LevelChangeRequest {
  level: MembershipLevel
  reason?: string
  totpCode?: string
}

export interface CollectionRequest {
  productId: number
  targetPrice?: string | number
}

export interface BrowseRecordRequest {
  productId: number
}

export interface CheckInResponse {
  checkInDate: string
  streakDays: number
  rewardPoints: number
  wallet: PointsWallet
}

export interface PointsLedgerEntry {
  id: number
  type: 'CHECK_IN' | 'PURCHASE' | 'ACTIVITY' | 'REDEEM' | 'ADJUST'
  points: number
  moneyEquivalent: string | number
  orderId?: number
  referenceKey?: string
  createdAt: string
}

export interface PriceDropScanResult {
  scanned: number
  reminders: number
}

export type SearchSort = 'RELEVANCE' | 'PRICE_ASC' | 'PRICE_DESC' | 'NEWEST' | 'HOT'

export interface SearchQuery {
  keyword?: string
  categoryId?: number
  attributeKey?: string
  attributeValue?: string
  sort?: SearchSort
  page?: number
  size?: number
}

export interface SearchProduct {
  productId: number
  categoryId?: number | null
  name: string
  title?: string
  imageUrl?: string
  originalPrice: string | number
  memberPrice?: string | number
  attributes: Record<string, unknown>
  score: number
}

export interface SearchPage {
  content: SearchProduct[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface SearchSuggestion {
  keyword: string
  source: string
  score: number
}

export interface HotKeyword {
  keyword: string
  score: number
}

export interface Recommendation {
  productId: number
  name: string
  title?: string
  imageUrl?: string
  reason: string
  score: number
}

export interface SearchProfileRequest {
  interestProfile: string
  tags: string[]
}

export interface SearchProfile {
  userId: number
  maskedInterestProfile: string
  tags: string[]
  updatedAt: string
  version: number
}

export interface SearchConversionRequest {
  keyword?: string
  productId: number
  source?: string
}

export type RiskDecision = 'ALLOW' | 'RATE_LIMIT' | 'TOTP_REQUIRED' | 'REVIEW' | 'BLOCK'
export type RiskSignalType =
  | 'DEVICE_MULTI_ACCOUNT'
  | 'PHONE_MULTI_ACCOUNT'
  | 'SECKILL_SCALPER'
  | 'SELF_BUY'
  | 'PRICE_ANOMALY'
  | 'HIGH_RISK_SCORE'
  | 'ACCOUNT_BLOCKED'
export type RiskReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'BLOCKED'

export interface RiskSignal {
  type: RiskSignalType
  weight: number
  detail?: string
}

export interface RiskAssessmentRequest {
  phone?: string
  deviceFingerprint?: string
  clientIp?: string
  productId?: number
  orderId?: number
  seckillActivityId?: number
  sellerUserId?: number
  priceBefore?: string | number
  priceAfter?: string | number
  totpCode?: string
}

export interface RiskAssessmentResponse {
  userId: number
  score: number
  decision: RiskDecision
  signals: RiskSignal[]
  reviewCaseId?: number
  productAutoUnlisted: boolean
  userTokensRevoked: boolean
  assessedAt: string
}

export interface RiskReviewCase {
  id: number
  userId: number
  orderId?: number
  productId?: number
  type: RiskSignalType
  score: number
  status: RiskReviewStatus
  detail?: string
  createdAt: string
  handledAt?: string
  handlerUserId?: number
  resolution?: string
}

export interface RiskReviewResolveRequest {
  status: Exclude<RiskReviewStatus, 'PENDING'>
  resolution?: string
  totpCode?: string
}

export type TrackingEventType =
  | 'PAGE_VIEW'
  | 'CLICK'
  | 'SEARCH'
  | 'PRODUCT_VIEW'
  | 'ADD_TO_CART'
  | 'ORDER_CREATED'
  | 'PAYMENT_SUCCESS'

export interface TrackingEventRequest {
  eventType: TrackingEventType
  sessionId?: string
  traceId?: string
  page?: string
  source?: string
  productId?: number
  categoryId?: number
  orderId?: number
  amount?: string | number
  attributes?: Record<string, string>
  occurredAt?: string
}

export interface TrackingEventResponse {
  id: number
  userId?: number
  sessionId: string
  traceId: string
  eventType: TrackingEventType
  page: string
  occurredAt: string
}

export interface UserProfileTag {
  userId: number
  profileSummary: string
  behaviorTags: string[]
  interestTags: string[]
  lastEventAt: string
  version: number
}

export interface ProductProfile {
  productId: number
  categoryId?: number
  tagVector: string[]
  salesCount: number
  reviewScore: string | number
  lastEventAt: string
  version: number
}

export interface FunnelStep {
  eventType: TrackingEventType
  count: number
  conversionRate: string | number
}

export interface RealtimeDashboard {
  pageViews: number
  uniqueVisitors: number
  orderCount: number
  paymentAmount: string | number
  funnel: FunnelStep[]
  generatedAt: string
  refreshIntervalSeconds: number
}

export type TenantStatus = 'TRIAL' | 'ACTIVE' | 'EXPIRED' | 'DOWNGRADED' | 'SUSPENDED'
export type TenantPlan = 'STARTER' | 'GROWTH' | 'ENTERPRISE'
export type TenantConfigType = 'PAYMENT' | 'LOGISTICS' | 'MARKETING' | 'ROLLOUT'
export type TenantBillStatus = 'GENERATED' | 'RECONCILED' | 'SUSPENDED'
export type TenantExportStatus = 'REQUESTED' | 'COMPLETED' | 'FAILED'

export interface Tenant {
  id: number
  code: string
  name: string
  status: TenantStatus
  plan: TenantPlan
  contactName?: string
  maskedContactPhone?: string
  createdAt: string
  expiresAt: string
  version: number
}

export interface TenantDashboard {
  activeTenants: number
  expiredTenants: number
  currentMonthOrders: number
  currentMonthRevenue: string | number
  tenants: Tenant[]
}

export interface TenantCreateRequest {
  code: string
  name: string
  plan: TenantPlan
  contactName?: string
  contactPhone?: string
  months?: number
}

export interface TenantRenewRequest {
  months: number
}

export interface TenantDowngradeRequest {
  plan: TenantPlan
}

export interface TenantConfig {
  id: number
  tenantId: number
  configType: TenantConfigType
  provider: string
  settings: Record<string, string>
  enabled: boolean
  updatedAt: string
  version: number
}

export interface TenantConfigRequest {
  configType: TenantConfigType
  provider?: string
  settings: Record<string, string>
  enabled: boolean
}

export interface TenantBill {
  id: number
  tenantId: number
  billingMonth: string
  plan: TenantPlan
  orderCount: number
  monthlyFee: string | number
  usageFee: string | number
  totalAmount: string | number
  paymentAmount: string | number
  status: TenantBillStatus
  generatedAt: string
  reconciledAt?: string
  version: number
}

export interface TenantBillGenerateRequest {
  billingMonth?: string
}

export interface TenantExportJob {
  id: number
  tenantId: number
  exportType: string
  status: TenantExportStatus
  encryptedArchivePath?: string
  requestedBy: number
  requestedAt: string
  completedAt?: string
  auditTraceId?: string
  errorMessage?: string
  version: number
}

export interface TenantExportRequest {
  exportType?: string
}
export type ToastKind = 'success' | 'warning' | 'error' | 'info'

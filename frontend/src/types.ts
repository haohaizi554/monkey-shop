import type { ApiId } from '@/api/ids'

export type Role = 'ADMIN' | 'USER' | string

export interface ApiResult<T> {
  code: string
  message: string
  data: T
  traceId?: string
}

export interface FieldViolation {
  field: string
  code: string
  message: string
}

export interface ApiProblem {
  type?: string
  title?: string
  detail?: string
  status?: number
  instance?: string
  code?: string
  traceId?: string
  fieldErrors?: FieldViolation[]
  retryAfterSeconds?: number
  retryAt?: string
}

export type ProblemDetail = ApiProblem

export interface PasswordPolicy {
  minLength: number
  requireUppercase: boolean
  requireLowercase: boolean
  requireDigit: boolean
  requireSpecial: boolean
  forbidWhitespace: boolean
}

export interface UserProfile {
  isLogin?: boolean
  identity?: Role
  username?: string
  avatar?: string
  maskedPhone?: string
  passwordChangeRequired?: boolean
  passwordExpired?: boolean
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
  id: ApiId
  shopId?: ApiId
  name: string
  breed: string
  price: string | number
  description?: string
  imageUrl: string
  stock: number
  categoryId?: ApiId
  categoryName?: string
  status?: ProductStatus
  memberPrice?: string | number
  strikePrice?: string | number
  regionPrices?: Record<string, string | number>
  attributes?: Record<string, unknown>
  detailJsonLd?: string
  skus?: CatalogSku[]
  selectedSkuId?: ApiId
}

export interface MonkeyRequest {
  id?: ApiId | null
  name: string
  breed: string
  price: string | number
  description?: string
  imageUrl: string
  stock: number
}

export interface Address {
  id: ApiId
  receiverName: string
  phone: string
  detailAddress: string
  isDefault: number
}

export type AddressRequest = Pick<Address, 'receiverName' | 'phone' | 'detailAddress'>

export interface Order {
  id: ApiId
  orderNo: string
  userId: ApiId
  buyerName: string
  buyerAvatar?: string
  productId: ApiId
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
  skuId: ApiId
  productName: string
  quantity: number
}

export interface OrderShipment {
  id: ApiId
  orderId: ApiId
  shipmentNo: string
  carrier: string
  trackingNo: string
  status: 'SHIPPED' | 'RECEIVED'
  shippedAt: string
  receivedAt?: string
  lines: OrderShipmentLine[]
}

export interface OrderShipmentLineRequest {
  skuId: ApiId
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
  skuId?: ApiId
  rating: number
  content?: string
  imageUrls: string[]
  anonymous: boolean
}

export interface OrderReview {
  id: ApiId
  orderId: ApiId
  userId: ApiId
  skuId: ApiId
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
  orderId: ApiId
  method: PaymentMethod
  bankCardNo?: string
  totpCode?: string
}

export interface PaymentResponse {
  id: ApiId
  paymentNo: string
  orderId: ApiId
  userId: ApiId
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
  ledgerId: ApiId
  paymentNo: string
  amount: string | number
  refundedAmount: string | number
  paymentStatus: PaymentStatus
  ledgerStatus: 'SUCCESS' | 'ACCEPTED' | 'FAILED'
  createTime: string
}

export type PaymentReconciliationStatus =
  'BALANCED' | 'DIFF' | 'PENDING_PROVIDER_DATA' | 'SUSPENDED'

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
  id: ApiId
  provider: PaymentMethod
  reportDate: string
  platformAmount: string | number
  providerAmount: string | number
  diffAmount: string | number
  issueCount: number
  status: PaymentReconciliationStatus
  createTime: string
}

export type LogisticsCarrier = 'SF' | 'ZTO' | 'YTO'
export type TrackingStatus = 'ORDERED' | 'PICKED_UP' | 'IN_TRANSIT' | 'OUT_FOR_DELIVERY' | 'SIGNED'
export type TrackingEvent = 'PICKUP' | 'TRANSIT' | 'DISPATCH' | 'SIGN'
export type FreightChargeMode = 'WEIGHT' | 'ITEM' | 'REGION'

export interface ShipmentCreateRequest {
  orderId: ApiId
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
  id: ApiId
  eventType: TrackingEvent
  fromStatus: TrackingStatus
  toStatus: TrackingStatus
  eventId: string
  eventTime: string
  location?: string
  remark?: string
}

export interface LogisticsTracking {
  id: ApiId
  trackingNo: string
  orderId: ApiId
  userId: ApiId
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

export interface CatalogSpecificationDimension {
  name: string
  values: string[]
}

/** Canonical catalog writes intentionally omit stock; inventory is SKU+warehouse owned. */
export interface CatalogSpuWriteRequest {
  categoryId: ApiId
  shopId: ApiId
  name: string
  title: string
  originalPrice: string | number
  memberPrice?: string | number | null
  strikePrice?: string | number | null
  regionPrices?: Record<string, string | number>
  attributes?: Record<string, unknown>
  detailJsonLd?: string | null
  supplierPrivateRemark?: string | null
  imageUrl?: string | null
  specifications: CatalogSpecificationDimension[]
}

export interface CatalogSku {
  id: ApiId
  spuId: ApiId
  skuCode: string
  specification: Record<string, string>
  originalPrice: string | number
  memberPrice?: string | number
  strikePrice?: string | number
  regionPrices: Record<string, string | number>
  active: boolean
}

export interface CatalogSpu {
  id: ApiId
  categoryId: ApiId
  shopId: ApiId
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
  spuId: ApiId
  salePrice: string | number
  strikePrice?: string | number
  strategy: string
}

export interface CategoryNode {
  id: ApiId
  parentId?: ApiId | null
  level: number
  code: string
  name: string
  children: CategoryNode[]
}

export interface WarehouseStock {
  skuId: ApiId
  warehouseId: ApiId
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
  skuId: ApiId
  warehouseId?: ApiId
  province?: string
  orderId?: ApiId
  quantity: number
  reservationKey: string
}

export interface InventoryCompensateRequest {
  skuId: ApiId
  warehouseId: ApiId
  orderId?: ApiId
  quantity: number
  idempotencyKey: string
}

export interface InventoryReservation {
  reservationKey: string
  skuId: ApiId
  warehouseId: ApiId
  orderId?: ApiId
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
  skuId: ApiId
  warehouseId: ApiId
  actualLocked: number
  expectedLocked: number
  actualDeducted: number
  expectedDeducted: number
}

export interface CouponClaimRequest {
  couponId: ApiId
  idempotencyKey: string
}

export interface CouponRedeemRequest {
  couponCode: string
  orderId: ApiId
}

export interface CouponReturnRequest {
  couponCode: string
  orderId: ApiId
}

export interface CouponWalletEntry {
  id: ApiId
  couponId: ApiId
  couponCode: string
  userId: ApiId
  status: 'CLAIMED' | 'USED' | 'RETURNED' | 'EXPIRED'
  orderId?: ApiId
  claimedAt: string
  usedAt?: string
}

export interface MarketingPriceRequest {
  orderAmount: string | number
  userId?: ApiId
  categoryId?: ApiId
  shopId?: ApiId
  couponCodes: string[]
  lines?: MarketingPriceLine[]
}

export interface MarketingPriceLine {
  lineId: ApiId
  amount: string | number
  categoryId?: ApiId
  shopId?: ApiId
}

export interface MarketingPriceAllocation {
  lineId: ApiId
  discountAmount: string | number
  appliedCoupons: string[]
}

export interface MarketingPriceQuote {
  originalAmount: string | number
  discountAmount: string | number
  payableAmount: string | number
  appliedCoupons: string[]
  allocations: MarketingPriceAllocation[]
}

export interface SeckillRequest {
  activityId: ApiId
  orderId?: ApiId
  quantity: number
  idempotencyKey: string
  turnstileToken?: string
}

export interface SeckillOrder {
  id: ApiId
  activityId: ApiId
  skuId: ApiId
  userId: ApiId
  orderId?: ApiId
  quantity: number
  idempotencyKey: string
  createdAt: string
}

export interface GroupBuyJoinRequest {
  activityId: ApiId
  teamId?: ApiId
  idempotencyKey: string
}

export interface GroupBuyTeam {
  id: ApiId
  activityId: ApiId
  skuId: ApiId
  leaderUserId: ApiId
  targetSize: number
  joinedCount: number
  status: 'OPEN' | 'SUCCEEDED' | 'CANCELLED'
  expiresAt: string
}

export interface CartAddItemRequest {
  skuId: ApiId
  shopId: ApiId
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
  skuId: ApiId
  shopId: ApiId
  productName: string
  productImage?: string
  unitPrice: string | number
  quantity: number
  selected: boolean
  lineAmount: string | number
  updatedAt: string
}

export interface Cart {
  userId: ApiId
  items: CartItem[]
  selectedQuantity: number
  selectedAmount: string | number
}

export interface CartCheckoutRequest {
  addressId: ApiId
  province?: string
  couponCodes: string[]
}

export interface CartCheckoutLine {
  id: ApiId
  skuId: ApiId
  shopId: ApiId
  categoryId?: ApiId
  productName: string
  productImage?: string
  quantity: number
  unitPrice: string | number
  originalAmount: string | number
  discountAmount: string | number
  payableAmount: string | number
  couponCodes: string[]
  reservationKey: string
  warehouseId?: ApiId
}

export interface CartSubOrder {
  id: ApiId
  shopId: ApiId
  orderNo: string
  originalAmount: string | number
  discountAmount: string | number
  payableAmount: string | number
  status: 'RESERVED' | 'CHECKED_OUT'
  lines: CartCheckoutLine[]
}

export interface CartCheckout {
  id: ApiId
  checkoutNo: string
  userId: ApiId
  addressId: ApiId
  originalAmount: string | number
  discountAmount: string | number
  payableAmount: string | number
  status: 'RESERVED' | 'CHECKED_OUT'
  province?: string
  createdAt: string
  subOrders: CartSubOrder[]
}

export type MembershipLevel = 'BASIC' | 'SILVER' | 'GOLD' | 'DIAMOND'

export type IdentityVerificationStatus = 'PENDING' | 'VERIFIED' | 'REJECTED'

export interface MemberProfile {
  userId: ApiId
  level: MembershipLevel
  growthValue: number
  identityStatus: IdentityVerificationStatus
  verified: boolean
  maskedRealName?: string
  maskedIdCardNo?: string
  identitySubmittedAt?: string
  identityReviewedAt?: string
  version: number
  benefits: string[]
}

export interface PointsWallet {
  userId: ApiId
  balance: number
  totalEarned: number
  totalSpent: number
  moneyEquivalent: string | number
  version: number
}

export interface MembershipCouponWalletEntry {
  id: ApiId
  couponId: ApiId
  couponCode: string
  status: 'CLAIMED' | 'USED' | 'RETURNED' | 'EXPIRED'
  orderId?: ApiId
  claimedAt: string
  usedAt?: string
}

export interface MemberCollection {
  id: ApiId
  productId: ApiId
  productName: string
  productImage?: string
  lastPrice: string | number
  targetPrice?: string | number
  priceDropNotified: boolean
  createTime: string
  updateTime: string
}

export interface BrowseHistoryEntry {
  productId: ApiId
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

export interface IdentityReviewRequest {
  status: Exclude<IdentityVerificationStatus, 'PENDING'>
  reason: string
  totpCode: string
}

export interface PointsEarnRequest {
  orderId?: ApiId
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
  productId: ApiId
  targetPrice?: string | number
}

export interface BrowseRecordRequest {
  productId: ApiId
}

export interface CheckInResponse {
  checkInDate: string
  streakDays: number
  rewardPoints: number
  wallet: PointsWallet
}

export interface PointsLedgerEntry {
  id: ApiId
  type: 'CHECK_IN' | 'PURCHASE' | 'ACTIVITY' | 'REDEEM' | 'ADJUST'
  points: number
  moneyEquivalent: string | number
  orderId?: ApiId
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
  categoryId?: ApiId
  attributeKey?: string
  attributeValue?: string
  minPrice?: string | number
  maxPrice?: string | number
  inStock?: boolean
  sort?: SearchSort
  page?: number
  size?: number
}

export interface SearchProduct {
  productId: ApiId
  categoryId?: ApiId | null
  name: string
  title?: string
  imageUrl?: string
  originalPrice: string | number
  memberPrice?: string | number
  attributes: Record<string, unknown>
  stock: number
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
  productId: ApiId
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
  userId: ApiId
  maskedInterestProfile: string
  tags: string[]
  updatedAt: string
  version: number
}

export interface SearchConversionRequest {
  keyword?: string
  productId: ApiId
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
  productId?: ApiId
  orderId?: ApiId
  seckillActivityId?: ApiId
  sellerUserId?: ApiId
  priceBefore?: string | number
  priceAfter?: string | number
  totpCode?: string
}

export interface RiskAssessmentResponse {
  userId: ApiId
  score: number
  decision: RiskDecision
  signals: RiskSignal[]
  reviewCaseId?: ApiId
  productAutoUnlisted: boolean
  userTokensRevoked: boolean
  assessedAt: string
}

export interface RiskReviewCase {
  id: ApiId
  userId: ApiId
  orderId?: ApiId
  productId?: ApiId
  type: RiskSignalType
  score: number
  status: RiskReviewStatus
  detail?: string
  createdAt: string
  handledAt?: string
  handlerUserId?: ApiId
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
  | 'UI_ERROR'

export type ClientTrackingEventType = Exclude<
  TrackingEventType,
  'ORDER_CREATED' | 'PAYMENT_SUCCESS'
>

export interface TrackingEventRequest {
  eventType: ClientTrackingEventType
  sessionId?: string
  traceId?: string
  page?: string
  source?: string
  productId?: ApiId
  categoryId?: ApiId
  orderId?: ApiId
  amount?: string | number
  attributes?: Record<string, string>
  occurredAt?: string
}

export interface TrackingEventResponse {
  id: ApiId
  userId?: ApiId
  sessionId: string
  traceId: string
  eventType: TrackingEventType
  page: string
  occurredAt: string
}

export interface UserProfileTag {
  userId: ApiId
  profileSummary: string
  behaviorTags: string[]
  interestTags: string[]
  lastEventAt: string
  version: number
}

export interface ProductProfile {
  productId: ApiId
  categoryId?: ApiId
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
export type TenantExportStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNAVAILABLE'

export interface Tenant {
  id: ApiId
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
  id: ApiId
  tenantId: ApiId
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
  id: ApiId
  tenantId: ApiId
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
  id: ApiId
  tenantId: ApiId
  exportType: string
  status: TenantExportStatus
  artifactAvailable: boolean
  requestedBy: ApiId
  requestedAt: string
  completedAt?: string
  auditTraceId?: string
  version: number
}

export interface TenantExportRequest {
  exportType?: string
}
export type ToastKind = 'success' | 'warning' | 'error' | 'info'

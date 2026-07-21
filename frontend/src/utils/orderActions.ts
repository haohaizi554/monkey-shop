export type ConsumerOrderAction =
  'pay' | 'receive' | 'requestReturn' | 'review' | 'logistics' | 'shipReturn' | 'hide'

export type ConsumerOrderStatus =
  | 'PAYMENT_PENDING'
  | 'PAID'
  | 'PARTIALLY_SHIPPED'
  | 'SHIPPED'
  | 'PARTIALLY_RECEIVED'
  | 'COMPLETED'
  | 'RETURN_REQUESTED'
  | 'WAITING_RETURN_SHIPMENT'
  | 'RETURN_SHIPPING'
  | 'REFUNDED'
  | 'CANCELLED'
  | 'UNKNOWN'

const statusAliases: Readonly<Record<string, ConsumerOrderStatus>> = Object.freeze({
  CREATED: 'PAYMENT_PENDING',
  PENDING: 'PAYMENT_PENDING',
  PENDING_PAYMENT: 'PAYMENT_PENDING',
  PAYMENT_PENDING: 'PAYMENT_PENDING',
  PAYMENT_COMPLETED: 'PAID',
  RETURN_APPROVED: 'WAITING_RETURN_SHIPMENT',
  PICKED_UP: 'SHIPPED',
  IN_TRANSIT: 'SHIPPED',
  OUT_FOR_DELIVERY: 'SHIPPED',
  SIGNED: 'COMPLETED',
  '\u5f85\u652f\u4ed8': 'PAYMENT_PENDING',
  '\u5df2\u652f\u4ed8': 'PAID',
  '\u90e8\u5206\u53d1\u8d27': 'PARTIALLY_SHIPPED',
  '\u5df2\u53d1\u8d27': 'SHIPPED',
  '\u90e8\u5206\u7b7e\u6536': 'PARTIALLY_RECEIVED',
  '\u5df2\u5b8c\u6210': 'COMPLETED',
  '\u7533\u8bf7\u9000\u8d27': 'RETURN_REQUESTED',
  '\u5f85\u9000\u8d27\u53d1\u8d27': 'WAITING_RETURN_SHIPMENT',
  '\u9000\u8d27\u4e2d': 'RETURN_SHIPPING',
  '\u5df2\u9000\u6b3e': 'REFUNDED',
})

const actionsByStatus: Readonly<Record<ConsumerOrderStatus, readonly ConsumerOrderAction[]>> =
  Object.freeze({
    PAYMENT_PENDING: ['pay'],
    PAID: ['logistics'],
    PARTIALLY_SHIPPED: ['logistics'],
    SHIPPED: ['receive', 'logistics'],
    PARTIALLY_RECEIVED: ['receive', 'logistics'],
    COMPLETED: ['requestReturn', 'review', 'logistics', 'hide'],
    RETURN_REQUESTED: ['logistics'],
    WAITING_RETURN_SHIPMENT: ['shipReturn', 'logistics'],
    RETURN_SHIPPING: ['logistics'],
    REFUNDED: ['hide'],
    CANCELLED: ['hide'],
    UNKNOWN: [],
  })

export function normalizeConsumerOrderStatus(status: string): ConsumerOrderStatus {
  const normalized = status.trim()
  if (normalized in statusAliases) {
    return statusAliases[normalized] ?? 'UNKNOWN'
  }
  if (normalized in actionsByStatus) {
    return normalized as ConsumerOrderStatus
  }
  return 'UNKNOWN'
}

export function consumerOrderActions(status: string): readonly ConsumerOrderAction[] {
  return actionsByStatus[normalizeConsumerOrderStatus(status)]
}

export function hasConsumerOrderAction(status: string, action: ConsumerOrderAction): boolean {
  return consumerOrderActions(status).includes(action)
}

export function consumerOrderStatusTone(
  status: string,
): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  const normalized = normalizeConsumerOrderStatus(status)
  if (normalized === 'COMPLETED' || normalized === 'PAID') {
    return 'success'
  }
  if (normalized === 'REFUNDED' || normalized === 'CANCELLED') {
    return 'info'
  }
  if (
    normalized === 'RETURN_REQUESTED' ||
    normalized === 'WAITING_RETURN_SHIPMENT' ||
    normalized === 'RETURN_SHIPPING'
  ) {
    return 'warning'
  }
  if (normalized === 'UNKNOWN') {
    return 'danger'
  }
  return 'primary'
}

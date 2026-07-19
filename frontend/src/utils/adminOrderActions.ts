export type AdminOrderAction =
  'ship' | 'approveReturn' | 'refundReturn' | 'viewPayment' | 'viewShipments'

export type AdminOrderStatus =
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'PARTIALLY_SHIPPED'
  | 'SHIPPED'
  | 'PARTIALLY_RECEIVED'
  | 'COMPLETED'
  | 'RETURN_REQUESTED'
  | 'WAITING_RETURN_SHIPMENT'
  | 'RETURN_SHIPPING'
  | 'REFUNDED'
  | 'UNKNOWN'

const aliases: Readonly<Record<string, AdminOrderStatus>> = Object.freeze({
  CREATED: 'PENDING_PAYMENT',
  PENDING: 'PENDING_PAYMENT',
  PAYMENT_PENDING: 'PENDING_PAYMENT',
  PAYMENT_COMPLETED: 'PAID',
  RETURN_APPROVED: 'WAITING_RETURN_SHIPMENT',
  '\u5f85\u652f\u4ed8': 'PENDING_PAYMENT',
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

const actionsByStatus: Readonly<Record<AdminOrderStatus, readonly AdminOrderAction[]>> =
  Object.freeze({
    PENDING_PAYMENT: ['viewPayment'],
    PAID: ['ship', 'viewPayment', 'viewShipments'],
    PARTIALLY_SHIPPED: ['ship', 'viewPayment', 'viewShipments'],
    SHIPPED: ['viewPayment', 'viewShipments'],
    PARTIALLY_RECEIVED: ['viewPayment', 'viewShipments'],
    COMPLETED: ['viewPayment', 'viewShipments'],
    RETURN_REQUESTED: ['approveReturn', 'viewPayment'],
    WAITING_RETURN_SHIPMENT: ['viewPayment'],
    RETURN_SHIPPING: ['refundReturn', 'viewPayment'],
    REFUNDED: ['viewPayment'],
    UNKNOWN: [],
  })

export function normalizeAdminOrderStatus(status: string): AdminOrderStatus {
  const normalized = status.trim()
  if (normalized in aliases) {
    return aliases[normalized] ?? 'UNKNOWN'
  }
  if (normalized in actionsByStatus) {
    return normalized as AdminOrderStatus
  }
  return 'UNKNOWN'
}

export function adminOrderActions(status: string): readonly AdminOrderAction[] {
  return actionsByStatus[normalizeAdminOrderStatus(status)]
}

export function hasAdminOrderAction(status: string, action: AdminOrderAction): boolean {
  return adminOrderActions(status).includes(action)
}

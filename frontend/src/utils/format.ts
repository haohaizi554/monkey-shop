export function money(value: string | number | undefined): string {
  const numeric = Number(value ?? 0)
  return new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: 'CNY',
    maximumFractionDigits: 2,
  }).format(Number.isFinite(numeric) ? numeric : 0)
}

export function dateTime(value?: string): string {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

export function orderStatusKey(status: string): string {
  const aliases: Record<string, string> = {
    '\u5df2\u652f\u4ed8': 'PAID',
    '\u90e8\u5206\u53d1\u8d27': 'PARTIALLY_SHIPPED',
    '\u5df2\u53d1\u8d27': 'SHIPPED',
    '\u90e8\u5206\u7b7e\u6536': 'PARTIALLY_RECEIVED',
    '\u5df2\u5b8c\u6210': 'COMPLETED',
    '\u7533\u8bf7\u9000\u8d27': 'RETURN_REQUESTED',
    '\u5f85\u9000\u8d27\u53d1\u8d27': 'WAITING_RETURN_SHIPMENT',
    '\u9000\u8d27\u4e2d': 'RETURN_SHIPPING',
    '\u5df2\u9000\u6b3e': 'REFUNDED',
  }
  return aliases[status] ?? status
}

export function orderStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    PAID: '已支付',
    PARTIALLY_SHIPPED: '部分发货',
    SHIPPED: '已发货',
    PARTIALLY_RECEIVED: '部分签收',
    COMPLETED: '已完成',
    RETURN_REQUESTED: '申请退货',
    WAITING_RETURN_SHIPMENT: '待寄回',
    RETURN_SHIPPING: '退货中',
    REFUNDED: '已退款',
  }
  return labels[orderStatusKey(status)] ?? status
}

export function statusType(status: string): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  const key = orderStatusKey(status)
  if (key === 'REFUNDED') {
    return 'info'
  }
  if (key.includes('RETURN')) {
    return 'warning'
  }
  if (key === 'COMPLETED') {
    return 'success'
  }
  if (key.includes('SHIPPED') || key.includes('RECEIVED')) {
    return 'primary'
  }
  return 'danger'
}

export function paymentMethodLabel(method: string): string {
  const labels: Record<string, string> = {
    WECHAT: '微信',
    ALIPAY: '支付宝',
    BANK_CARD: '银行卡',
  }
  return labels[method] ?? method
}

export function paymentStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    PENDING: '待支付',
    PAID: '已支付',
    PARTIALLY_REFUNDED: '部分退款',
    REFUNDED: '已退款',
    SUSPENDED: '已挂起',
    FAILED: '支付失败',
  }
  return labels[status] ?? status
}

export function paymentStatusType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  if (status === 'PAID') {
    return 'success'
  }
  if (status === 'PENDING' || status === 'PARTIALLY_REFUNDED') {
    return 'warning'
  }
  if (status === 'REFUNDED') {
    return 'info'
  }
  return 'danger'
}

export function paymentReconciliationStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    BALANCED: '账实相符',
    DIFF: '存在差异',
    PENDING_PROVIDER_DATA: '等待渠道账单',
    SUSPENDED: '已挂起',
  }
  return labels[status] ?? status
}

export function paymentReconciliationStatusType(
  status: string,
): 'success' | 'warning' | 'info' | 'danger' {
  if (status === 'BALANCED') {
    return 'success'
  }
  if (status === 'PENDING_PROVIDER_DATA') {
    return 'info'
  }
  if (status === 'DIFF') {
    return 'warning'
  }
  return 'danger'
}

export function trackingStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    ORDERED: '已下单',
    PICKED_UP: '已揽收',
    IN_TRANSIT: '运输中',
    OUT_FOR_DELIVERY: '派送中',
    SIGNED: '已签收',
  }
  return labels[status] ?? status
}

export function trackingStatusType(status: string): 'primary' | 'success' | 'warning' | 'info' {
  if (status === 'SIGNED') {
    return 'success'
  }
  if (status === 'OUT_FOR_DELIVERY') {
    return 'warning'
  }
  if (status === 'IN_TRANSIT' || status === 'PICKED_UP') {
    return 'primary'
  }
  return 'info'
}

export function trackingEventLabel(event: string): string {
  const labels: Record<string, string> = {
    PICKUP: '揽收',
    TRANSIT: '运输',
    DISPATCH: '派送',
    SIGN: '签收',
  }
  return labels[event] ?? event
}

export function couponStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    CLAIMED: '已领取',
    USED: '已核销',
    RETURNED: '已退回',
    EXPIRED: '已过期',
  }
  return labels[status] ?? status
}

export function groupBuyStatusLabel(status: string): string {
  const labels: Record<string, string> = {
    OPEN: '拼团中',
    SUCCEEDED: '已成团',
    CANCELLED: '已取消',
  }
  return labels[status] ?? status
}

export function membershipLevelLabel(level: string): string {
  const labels: Record<string, string> = {
    BASIC: '基础会员',
    SILVER: '银卡会员',
    GOLD: '金卡会员',
    DIAMOND: '钻石会员',
  }
  return labels[level] ?? level
}

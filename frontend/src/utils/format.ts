export function money(value: string | number | undefined): string {
  const numeric = Number(value ?? 0)
  return new Intl.NumberFormat(undefined, {
    style: 'currency',
    currency: 'CNY',
    maximumFractionDigits: 2,
  }).format(Number.isFinite(numeric) ? numeric : 0)
}

function currentLocale(): 'en' | 'zh' {
  if (typeof localStorage === 'undefined') {
    return 'zh'
  }
  return localStorage.getItem('monkeyshop-locale') === 'en' ? 'en' : 'zh'
}

function localizedLabel(map: Record<string, [string, string]>, key: string): string {
  const entry = map[key]
  if (!entry) {
    return currentLocale() === 'en' ? 'Unknown' : '未知'
  }
  return currentLocale() === 'en' ? entry[1] : entry[0]
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
  const labels: Record<string, [string, string]> = {
    PAID: ['已支付', 'Paid'],
    PARTIALLY_SHIPPED: ['部分发货', 'Partially shipped'],
    SHIPPED: ['已发货', 'Shipped'],
    PARTIALLY_RECEIVED: ['部分签收', 'Partially received'],
    COMPLETED: ['已完成', 'Completed'],
    RETURN_REQUESTED: ['申请退货', 'Return requested'],
    WAITING_RETURN_SHIPMENT: ['待寄回', 'Awaiting return shipment'],
    RETURN_SHIPPING: ['退货中', 'Returning'],
    REFUNDED: ['已退款', 'Refunded'],
  }
  return localizedLabel(labels, orderStatusKey(status))
}

export function statusType(status: string): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  const key = orderStatusKey(status)
  if (key === 'PAID') {
    return 'success'
  }
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
  const labels: Record<string, [string, string]> = {
    WECHAT: ['微信支付', 'WeChat Pay'],
    ALIPAY: ['支付宝', 'Alipay'],
    BANK_CARD: ['银行卡', 'Bank card'],
  }
  return localizedLabel(labels, method)
}

export function paymentStatusLabel(status: string): string {
  const labels: Record<string, [string, string]> = {
    PENDING: ['待支付', 'Pending'],
    PAID: ['已支付', 'Paid'],
    PARTIALLY_REFUNDED: ['部分退款', 'Partially refunded'],
    REFUNDED: ['已退款', 'Refunded'],
    SUSPENDED: ['已挂起', 'Suspended'],
    FAILED: ['支付失败', 'Failed'],
  }
  return localizedLabel(labels, status)
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
  const labels: Record<string, [string, string]> = {
    BALANCED: ['账实相符', 'Balanced'],
    DIFF: ['存在差异', 'Discrepancy'],
    PENDING_PROVIDER_DATA: ['等待渠道账单', 'Awaiting provider data'],
    SUSPENDED: ['已挂起', 'Suspended'],
  }
  return localizedLabel(labels, status)
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
  const labels: Record<string, [string, string]> = {
    ORDERED: ['已下单', 'Ordered'],
    PICKED_UP: ['已揽收', 'Picked up'],
    IN_TRANSIT: ['运输中', 'In transit'],
    OUT_FOR_DELIVERY: ['派送中', 'Out for delivery'],
    SIGNED: ['已签收', 'Signed'],
  }
  return localizedLabel(labels, status)
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
  const labels: Record<string, [string, string]> = {
    PICKUP: ['揽收', 'Pickup'],
    TRANSIT: ['运输', 'Transit'],
    DISPATCH: ['派送', 'Dispatch'],
    SIGN: ['签收', 'Sign'],
  }
  return localizedLabel(labels, event)
}

export function couponStatusLabel(status: string): string {
  const labels: Record<string, [string, string]> = {
    CLAIMED: ['已领取', 'Claimed'],
    USED: ['已核销', 'Used'],
    RETURNED: ['已退回', 'Returned'],
    EXPIRED: ['已过期', 'Expired'],
  }
  return localizedLabel(labels, status)
}

export function groupBuyStatusLabel(status: string): string {
  const labels: Record<string, [string, string]> = {
    OPEN: ['拼团中', 'Open'],
    SUCCEEDED: ['已成团', 'Succeeded'],
    CANCELLED: ['已取消', 'Cancelled'],
  }
  return localizedLabel(labels, status)
}

export function membershipLevelLabel(level: string): string {
  const labels: Record<string, [string, string]> = {
    BASIC: ['基础会员', 'Basic'],
    SILVER: ['银卡会员', 'Silver'],
    GOLD: ['金卡会员', 'Gold'],
    DIAMOND: ['钻石会员', 'Diamond'],
  }
  return localizedLabel(labels, level)
}

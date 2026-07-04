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

export function statusType(status: string): 'success' | 'warning' | 'info' | 'danger' {
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
  return 'danger'
}

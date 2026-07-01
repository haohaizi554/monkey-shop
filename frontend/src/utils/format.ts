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
    已支付: 'PAID',
    已发货: 'SHIPPED',
    已完成: 'COMPLETED',
    申请退货: 'RETURN_REQUESTED',
    待退货发货: 'WAITING_RETURN_SHIPMENT',
    退货中: 'RETURN_SHIPPING',
    已退款: 'REFUNDED',
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

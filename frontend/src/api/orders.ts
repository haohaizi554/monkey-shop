import { request } from './http'
import type { ApiId } from './ids'
import type { PageEnvelope } from './page'
import type {
  Order,
  OrderReview,
  OrderReviewRequest,
  OrderShipment,
  OrderShipmentRequest,
} from '@/types'

export interface OrderSummary extends Order {
  checkoutId?: ApiId
  checkoutSubOrderId?: ApiId
  shopId?: ApiId
  originalAmount?: string | number
  discountAmount?: string | number
  lines: OrderLineSummary[]
}

export interface OrderLineSummary {
  checkoutLineId?: ApiId
  skuId: ApiId
  shopId?: ApiId
  categoryId?: ApiId
  productName: string
  productImage?: string
  quantity: number
  unitPrice: string | number
  originalAmount: string | number
  discountAmount: string | number
  payableAmount: string | number
  couponCodes: string[]
}

export interface OrderPageQuery {
  page: number
  size: number
  status?: string
  keyword?: string
  signal?: AbortSignal
}

export type OrderShipmentLinePayload = Omit<OrderShipmentRequest['lines'][number], 'skuId'> & {
  skuId: ApiId
}

export type OrderShipmentPayload = Omit<OrderShipmentRequest, 'lines'> & {
  lines: OrderShipmentLinePayload[]
}

export function myOrderPage(query: OrderPageQuery): Promise<PageEnvelope<OrderSummary>> {
  const { signal, ...params } = query
  return request<PageEnvelope<OrderSummary>>({ url: '/orders/my', params, signal })
}

export function myOrder(id: ApiId): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/${id}` })
}

export function allOrderPage(query: OrderPageQuery): Promise<PageEnvelope<OrderSummary>> {
  const { signal, ...params } = query
  return request<PageEnvelope<OrderSummary>>({ url: '/orders/all', params, signal })
}

export function createOrder(
  monkeyId: ApiId,
  addressId: ApiId,
  idempotencyKey?: string,
): Promise<OrderSummary> {
  return request<OrderSummary>({
    url: '/orders/create',
    method: 'POST',
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    data: { monkeyId, addressId },
  })
}

export async function hideOrder(id: ApiId): Promise<void> {
  await request<void>({ url: `/orders/${id}`, method: 'DELETE' })
}

export function receiveOrder(id: ApiId): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/receive/${id}`, method: 'POST' })
}

export function applyReturn(id: ApiId): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/return/apply/${id}`, method: 'POST' })
}

export function shipReturn(id: ApiId): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/return/ship/${id}`, method: 'POST' })
}

export function shipOrder(id: ApiId): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/ship/${id}`, method: 'POST' })
}

export function createShipment(
  id: ApiId,
  payload: OrderShipmentPayload,
  idempotencyKey?: string,
): Promise<OrderShipment> {
  return request<OrderShipment>({
    url: `/orders/shipments/${id}`,
    method: 'POST',
    data: payload,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
}

export function orderShipments(id: ApiId): Promise<OrderShipment[]> {
  return request<OrderShipment[]>({ url: `/orders/${id}/shipments` })
}

export function adminOrderShipments(id: ApiId): Promise<OrderShipment[]> {
  return request<OrderShipment[]>({ url: `/orders/admin/${id}/shipments` })
}

export function receiveShipment(id: ApiId): Promise<OrderShipment> {
  return request<OrderShipment>({ url: `/orders/shipments/receive/${id}`, method: 'POST' })
}

export function approveReturn(id: ApiId): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/return/approve/${id}`, method: 'POST' })
}

export function confirmReturn(id: ApiId): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/return/confirm/${id}`, method: 'POST' })
}

export function reviewOrder(
  id: ApiId,
  payload: Omit<OrderReviewRequest, 'skuId'> & { skuId: ApiId },
): Promise<OrderReview> {
  return request<OrderReview>({ url: `/orders/review/${id}`, method: 'POST', data: payload })
}

export function orderReviews(id: ApiId): Promise<OrderReview[]> {
  return request<OrderReview[]>({ url: `/orders/review/${id}` })
}

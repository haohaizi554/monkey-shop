import { request } from './http'
import { requestAllPageContent } from './page'
import type {
  Order,
  OrderReview,
  OrderReviewRequest,
  OrderShipment,
  OrderShipmentRequest,
} from '@/types'

export interface OrderSummary extends Order {
  checkoutId?: number
  checkoutSubOrderId?: number
  shopId?: number
  originalAmount?: string | number
  discountAmount?: string | number
  lines: OrderLineSummary[]
}

export interface OrderLineSummary {
  checkoutLineId?: number
  skuId: number
  shopId?: number
  categoryId?: number
  productName: string
  productImage?: string
  quantity: number
  unitPrice: string | number
  originalAmount: string | number
  discountAmount: string | number
  payableAmount: string | number
  couponCodes: string[]
}

export function myOrders(): Promise<OrderSummary[]> {
  return requestAllPageContent<OrderSummary>({ url: '/orders/my' })
}

export function myOrder(id: number): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/${id}` })
}

export function allOrders(): Promise<OrderSummary[]> {
  return requestAllPageContent<OrderSummary>({ url: '/orders/all' })
}

export function createOrder(
  monkeyId: number,
  addressId: number,
  idempotencyKey?: string,
): Promise<OrderSummary> {
  return request<OrderSummary>({
    url: '/orders/create',
    method: 'POST',
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    data: { monkeyId, addressId },
  })
}

export async function hideOrder(id: number): Promise<void> {
  await request<void>({ url: `/orders/${id}`, method: 'DELETE' })
}

export function receiveOrder(id: number): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/receive/${id}`, method: 'POST' })
}

export function applyReturn(id: number): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/return/apply/${id}`, method: 'POST' })
}

export function shipReturn(id: number): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/return/ship/${id}`, method: 'POST' })
}

export function shipOrder(id: number): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/ship/${id}`, method: 'POST' })
}

export function createShipment(
  id: number,
  payload: OrderShipmentRequest,
  idempotencyKey?: string,
): Promise<OrderShipment> {
  return request<OrderShipment>({
    url: `/orders/shipments/${id}`,
    method: 'POST',
    data: payload,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
}

export function orderShipments(id: number): Promise<OrderShipment[]> {
  return request<OrderShipment[]>({ url: `/orders/${id}/shipments` })
}

export function adminOrderShipments(id: number): Promise<OrderShipment[]> {
  return request<OrderShipment[]>({ url: `/orders/admin/${id}/shipments` })
}

export function receiveShipment(id: number): Promise<OrderShipment> {
  return request<OrderShipment>({ url: `/orders/shipments/receive/${id}`, method: 'POST' })
}

export function approveReturn(id: number): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/return/approve/${id}`, method: 'POST' })
}

export function confirmReturn(id: number): Promise<OrderSummary> {
  return request<OrderSummary>({ url: `/orders/return/confirm/${id}`, method: 'POST' })
}

export function reviewOrder(id: number, payload: OrderReviewRequest): Promise<OrderReview> {
  return request<OrderReview>({ url: `/orders/review/${id}`, method: 'POST', data: payload })
}

export function orderReviews(id: number): Promise<OrderReview[]> {
  return request<OrderReview[]>({ url: `/orders/review/${id}` })
}

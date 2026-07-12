import { request } from './http'
import type {
  Order,
  OrderReview,
  OrderReviewRequest,
  OrderShipment,
  OrderShipmentRequest,
} from '@/types'

export function myOrders(): Promise<Order[]> {
  return request<Order[]>({ url: '/orders/my' })
}

export function allOrders(): Promise<Order[]> {
  return request<Order[]>({ url: '/orders/all' })
}

export function createOrder(
  monkeyId: number,
  addressId: number,
  idempotencyKey?: string,
): Promise<Order> {
  return request<Order>({
    url: '/orders/create',
    method: 'POST',
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
    data: { monkeyId, addressId },
  })
}

export async function hideOrder(id: number): Promise<void> {
  await request<void>({ url: `/orders/${id}`, method: 'DELETE' })
}

export function receiveOrder(id: number): Promise<Order> {
  return request<Order>({ url: `/orders/receive/${id}`, method: 'POST' })
}

export function applyReturn(id: number): Promise<Order> {
  return request<Order>({ url: `/orders/return/apply/${id}`, method: 'POST' })
}

export function shipReturn(id: number): Promise<Order> {
  return request<Order>({ url: `/orders/return/ship/${id}`, method: 'POST' })
}

export function shipOrder(id: number): Promise<Order> {
  return request<Order>({ url: `/orders/ship/${id}`, method: 'POST' })
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

export function receiveShipment(id: number): Promise<OrderShipment> {
  return request<OrderShipment>({ url: `/orders/shipments/receive/${id}`, method: 'POST' })
}

export function approveReturn(id: number): Promise<Order> {
  return request<Order>({ url: `/orders/return/approve/${id}`, method: 'POST' })
}

export function confirmReturn(id: number): Promise<Order> {
  return request<Order>({ url: `/orders/return/confirm/${id}`, method: 'POST' })
}

export function reviewOrder(id: number, payload: OrderReviewRequest): Promise<OrderReview> {
  return request<OrderReview>({ url: `/orders/review/${id}`, method: 'POST', data: payload })
}

export function orderReviews(id: number): Promise<OrderReview[]> {
  return request<OrderReview[]>({ url: `/orders/review/${id}` })
}

import { request } from './http'
import type { Order } from '@/types'

export function myOrders(): Promise<Order[]> {
  return request<Order[]>({ url: '/orders/my' })
}

export function allOrders(): Promise<Order[]> {
  return request<Order[]>({ url: '/orders/all' })
}

export function createOrder(monkeyId: number, addressId: number): Promise<Order> {
  return request<Order>({
    url: '/orders/create',
    method: 'POST',
    headers: {
      'Idempotency-Key': crypto.randomUUID(),
    },
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

export function approveReturn(id: number): Promise<Order> {
  return request<Order>({ url: `/orders/return/approve/${id}`, method: 'POST' })
}

export function confirmReturn(id: number): Promise<Order> {
  return request<Order>({ url: `/orders/return/confirm/${id}`, method: 'POST' })
}

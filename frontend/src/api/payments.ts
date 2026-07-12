import { request } from './http'
import type {
  PaymentCreateRequest,
  PaymentReconciliationRequest,
  PaymentReconciliationResponse,
  PaymentRefundRequest,
  PaymentRefundResponse,
  PaymentResponse,
} from '@/types'

export function createPayment(
  payload: PaymentCreateRequest,
  idempotencyKey?: string,
): Promise<PaymentResponse> {
  return request<PaymentResponse>({
    url: '/payments/pay',
    method: 'POST',
    data: payload,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
}

export function paymentForOrder(orderId: number): Promise<PaymentResponse> {
  return request<PaymentResponse>({ url: `/payments/orders/${orderId}` })
}

export function adminPaymentForOrder(orderId: number): Promise<PaymentResponse> {
  return request<PaymentResponse>({ url: `/payments/admin/orders/${orderId}` })
}

export function refundPayment(
  payload: PaymentRefundRequest,
  idempotencyKey?: string,
): Promise<PaymentRefundResponse> {
  return request<PaymentRefundResponse>({
    url: '/payments/refund',
    method: 'POST',
    data: payload,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
}

export function adminRefundPayment(
  payload: PaymentRefundRequest,
  idempotencyKey?: string,
): Promise<PaymentRefundResponse> {
  return request<PaymentRefundResponse>({
    url: '/payments/admin/refund',
    method: 'POST',
    data: payload,
    headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
  })
}

export function reconcilePayment(
  payload: PaymentReconciliationRequest,
): Promise<PaymentReconciliationResponse> {
  return request<PaymentReconciliationResponse>({
    url: '/payments/reconciliation',
    method: 'POST',
    data: payload,
  })
}

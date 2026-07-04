import { request } from './http'
import type {
  PaymentCreateRequest,
  PaymentReconciliationRequest,
  PaymentReconciliationResponse,
  PaymentRefundRequest,
  PaymentRefundResponse,
  PaymentResponse,
} from '@/types'

export function createPayment(payload: PaymentCreateRequest): Promise<PaymentResponse> {
  return request<PaymentResponse>({
    url: '/payments/pay',
    method: 'POST',
    data: payload,
  })
}

export function paymentForOrder(orderId: number): Promise<PaymentResponse> {
  return request<PaymentResponse>({ url: `/payments/orders/${orderId}` })
}

export function refundPayment(payload: PaymentRefundRequest): Promise<PaymentRefundResponse> {
  return request<PaymentRefundResponse>({
    url: '/payments/refund',
    method: 'POST',
    data: payload,
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

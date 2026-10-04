import { describe, expectTypeOf, it } from 'vitest'
import { createOrder } from '@/api/orders'
import { adminRefundPayment, createPayment, refundPayment } from '@/api/payments'

describe('required idempotency-key API contract', () => {
  it('requires a key for every backend-mutating order/payment intent', () => {
    expectTypeOf<Parameters<typeof createOrder>[2]>().toEqualTypeOf<string>()
    expectTypeOf<Parameters<typeof createPayment>[1]>().toEqualTypeOf<string>()
    expectTypeOf<Parameters<typeof refundPayment>[1]>().toEqualTypeOf<string>()
    expectTypeOf<Parameters<typeof adminRefundPayment>[1]>().toEqualTypeOf<string>()
  })
})

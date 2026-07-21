import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => vi.fn().mockResolvedValue({}))

vi.mock('@/api/http', () => ({
  request: requestMock,
}))

import { createOrder, createShipment as createOrderShipment } from '@/api/orders'
import { directCheckoutCart } from '@/api/cart'
import { adminEarnPoints, checkIn, redeemPoints } from '@/api/membership'
import {
  adminPaymentForOrder,
  adminRefundPayment,
  createPayment,
  refundPayment,
} from '@/api/payments'

describe('business API idempotency keys', () => {
  beforeEach(() => {
    requestMock.mockClear()
  })

  it('forwards caller supplied keys for order, payment, refund, and shipment intents', async () => {
    await createOrder(11, 22, 'order-key')
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ headers: { 'Idempotency-Key': 'order-key' } }),
    )

    const directCheckout = {
      skuId: 101,
      shopId: 9,
      quantity: 3,
      addressId: 22,
      province: 'CN-BJ',
      couponCodes: [],
    }
    await directCheckoutCart(directCheckout, 'direct-checkout-key')
    expect(requestMock).toHaveBeenLastCalledWith({
      url: '/cart/checkout/direct',
      method: 'POST',
      headers: { 'Idempotency-Key': 'direct-checkout-key' },
      data: directCheckout,
    })

    await createPayment({ orderId: 11, method: 'WECHAT' }, 'payment-key')
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ headers: { 'Idempotency-Key': 'payment-key' } }),
    )

    await refundPayment({ paymentNo: 'PAY-11', amount: '10.00' }, 'refund-key')
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ headers: { 'Idempotency-Key': 'refund-key' } }),
    )

    await adminPaymentForOrder(11)
    expect(requestMock).toHaveBeenLastCalledWith({ url: '/payments/admin/orders/11' })

    await adminRefundPayment({ paymentNo: 'PAY-11', amount: '10.00' }, 'admin-refund-key')
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({
        url: '/payments/admin/refund',
        headers: { 'Idempotency-Key': 'admin-refund-key' },
      }),
    )

    await createOrderShipment(11, { carrier: 'SF', lines: [] }, 'order-shipment-key')
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ headers: { 'Idempotency-Key': 'order-shipment-key' } }),
    )

    await checkIn('membership-check-in-key')
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ headers: { 'Idempotency-Key': 'membership-check-in-key' } }),
    )

    await adminEarnPoints(
      7,
      { amount: 100, referenceKey: 'support adjustment' },
      'membership-earn-key',
    )
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({
        url: '/membership/admin/7/points/earn',
        headers: { 'Idempotency-Key': 'membership-earn-key' },
      }),
    )

    await redeemPoints({ points: 100 }, 'membership-redeem-key')
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ headers: { 'Idempotency-Key': 'membership-redeem-key' } }),
    )
  })

  it('keeps all existing call signatures valid when no explicit key is supplied', async () => {
    await createOrder(11, 22)
    await createPayment({ orderId: 11, method: 'ALIPAY' })
    await refundPayment({ paymentNo: 'PAY-11', amount: 10 })
    await createOrderShipment(11, { lines: [] })

    expect(requestMock).toHaveBeenCalledTimes(4)
    for (const [config] of requestMock.mock.calls) {
      expect(config.headers).toBeUndefined()
    }
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => vi.fn().mockResolvedValue({}))

vi.mock('@/api/http', () => ({
  request: requestMock,
}))

import { createOrder, createShipment as createOrderShipment } from '@/api/orders'
import { checkIn, earnPoints, redeemPoints } from '@/api/membership'
import {
  adminPaymentForOrder,
  adminRefundPayment,
  createPayment,
  refundPayment,
} from '@/api/payments'
import { createShipment as createLogisticsShipment } from '@/api/logistics'

describe('business API idempotency keys', () => {
  beforeEach(() => {
    requestMock.mockClear()
  })

  it('forwards caller supplied keys for order, payment, refund, and shipment intents', async () => {
    await createOrder(11, 22, 'order-key')
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ headers: { 'Idempotency-Key': 'order-key' } }),
    )

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

    await createLogisticsShipment(
      { orderId: 11, carrier: 'SF', weightKg: 1, itemCount: 1 },
      'logistics-shipment-key',
    )
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ headers: { 'Idempotency-Key': 'logistics-shipment-key' } }),
    )

    await checkIn('membership-check-in-key')
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ headers: { 'Idempotency-Key': 'membership-check-in-key' } }),
    )

    await earnPoints({ amount: 100 }, 'membership-earn-key')
    expect(requestMock).toHaveBeenLastCalledWith(
      expect.objectContaining({ headers: { 'Idempotency-Key': 'membership-earn-key' } }),
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
    await createLogisticsShipment({
      orderId: 11,
      carrier: 'ZTO',
      weightKg: '1.5',
      itemCount: 2,
    })

    expect(requestMock).toHaveBeenCalledTimes(5)
    for (const [config] of requestMock.mock.calls) {
      expect(config.headers).toBeUndefined()
    }
  })
})

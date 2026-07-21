import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => vi.fn().mockResolvedValue({}))

vi.mock('@/api/http', () => ({
  request: requestMock,
}))

import { logisticsForOrder } from '@/api/logistics'
import { createOrder, createShipment, myOrder, orderReviews, reviewOrder } from '@/api/orders'
import { adminPaymentForOrder, createPayment, paymentForOrder } from '@/api/payments'

const SNOWFLAKE_ID = '338329504114688001'

describe('Snowflake API IDs', () => {
  beforeEach(() => {
    requestMock.mockClear()
  })

  it('keeps string IDs exact in order, payment, and logistics URLs', async () => {
    await myOrder(SNOWFLAKE_ID)
    await orderReviews(SNOWFLAKE_ID)
    await paymentForOrder(SNOWFLAKE_ID)
    await adminPaymentForOrder(SNOWFLAKE_ID)
    await logisticsForOrder(SNOWFLAKE_ID)

    expect(requestMock.mock.calls.map(([config]) => config.url)).toEqual([
      `/orders/${SNOWFLAKE_ID}`,
      `/orders/review/${SNOWFLAKE_ID}`,
      `/payments/orders/${SNOWFLAKE_ID}`,
      `/payments/admin/orders/${SNOWFLAKE_ID}`,
      `/logistics/orders/${SNOWFLAKE_ID}`,
    ])
  })

  it('keeps string IDs exact in order and payment request bodies', async () => {
    await createOrder(SNOWFLAKE_ID, SNOWFLAKE_ID)
    await createPayment({ orderId: SNOWFLAKE_ID, method: 'WECHAT' })
    await reviewOrder(SNOWFLAKE_ID, {
      skuId: SNOWFLAKE_ID,
      rating: 5,
      content: 'Exact ID',
      imageUrls: [],
      anonymous: false,
    })
    await createShipment(SNOWFLAKE_ID, {
      carrier: 'SF',
      lines: [
        {
          skuId: SNOWFLAKE_ID,
          quantity: 1,
          orderedQuantity: 1,
        },
      ],
    })

    expect(requestMock).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({
        data: { monkeyId: SNOWFLAKE_ID, addressId: SNOWFLAKE_ID },
      }),
    )
    expect(requestMock).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ data: { orderId: SNOWFLAKE_ID, method: 'WECHAT' } }),
    )
    expect(requestMock).toHaveBeenNthCalledWith(
      3,
      expect.objectContaining({
        url: `/orders/review/${SNOWFLAKE_ID}`,
        data: expect.objectContaining({ skuId: SNOWFLAKE_ID }),
      }),
    )
    expect(requestMock).toHaveBeenNthCalledWith(
      4,
      expect.objectContaining({
        url: `/orders/shipments/${SNOWFLAKE_ID}`,
        data: expect.objectContaining({
          lines: [expect.objectContaining({ skuId: SNOWFLAKE_ID })],
        }),
      }),
    )
  })

  it('keeps ordinary numeric IDs compatible', async () => {
    await myOrder(101)
    await createPayment({ orderId: 101, method: 'ALIPAY' })

    expect(requestMock).toHaveBeenNthCalledWith(1, { url: '/orders/101' })
    expect(requestMock).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ data: { orderId: 101, method: 'ALIPAY' } }),
    )
  })
})

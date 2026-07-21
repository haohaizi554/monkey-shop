import { describe, expect, it } from 'vitest'
import type { OrderSummary } from '@/api/orders'
import { orderDisplayLines, shipmentLinesForOrder } from './orderLineContract'

const baseOrder: OrderSummary = {
  id: 11,
  orderNo: 'ORD-11',
  userId: 42,
  buyerName: 'buyer',
  productId: 501,
  productName: 'Legacy snapshot',
  productImage: '/images/legacy.png',
  price: 120,
  receiverName: 'Ada',
  receiverPhone: '13800138000',
  addressSnapshot: 'Hangzhou',
  status: 'PAID',
  createTime: '2026-07-21T09:00:00',
  lines: [],
}

describe('order line contract', () => {
  it('builds shipment requests from every persisted order line', () => {
    const order: OrderSummary = {
      ...baseOrder,
      lines: [
        {
          checkoutLineId: 101,
          skuId: 501,
          shopId: 88,
          categoryId: 9,
          productName: 'Momo',
          productImage: '/images/momo.png',
          quantity: 2,
          unitPrice: 50,
          originalAmount: 100,
          discountAmount: 10,
          payableAmount: 90,
          couponCodes: ['PLATFORM-20'],
        },
        {
          checkoutLineId: 102,
          skuId: 502,
          shopId: 88,
          categoryId: 9,
          productName: 'Kiki',
          productImage: '/images/kiki.png',
          quantity: 3,
          unitPrice: 30,
          originalAmount: 90,
          discountAmount: 0,
          payableAmount: 90,
          couponCodes: [],
        },
      ],
    }

    expect(shipmentLinesForOrder(order)).toEqual([
      { skuId: 501, productName: 'Momo', quantity: 2, orderedQuantity: 2 },
      { skuId: 502, productName: 'Kiki', quantity: 3, orderedQuantity: 3 },
    ])
  })

  it('uses the legacy product snapshot only when persisted lines are absent', () => {
    expect(orderDisplayLines(baseOrder)).toEqual([
      expect.objectContaining({
        skuId: 501,
        productName: 'Legacy snapshot',
        quantity: 1,
      }),
    ])
    expect(shipmentLinesForOrder(baseOrder)).toEqual([
      {
        skuId: 501,
        productName: 'Legacy snapshot',
        quantity: 1,
        orderedQuantity: 1,
      },
    ])
  })
})

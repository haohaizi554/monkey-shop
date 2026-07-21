import { beforeEach, describe, expect, it } from 'vitest'
import {
  buildDirectCheckoutIntent,
  checkoutDiscountTotals,
  checkoutOrderIds,
  normalizeCartCheckoutIntent,
} from '@/composables/useCheckout'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'

describe('cart checkout flow helpers', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('normalizes one checkout intent before deriving a retry-stable key', () => {
    const original = normalizeCartCheckoutIntent({
      addressId: 3,
      province: ' CN-ZJ ',
      couponCodes: [' SAVE-8 ', '', 'SHOP-2'],
    })
    const equivalent = normalizeCartCheckoutIntent({
      addressId: 3,
      province: 'CN-ZJ',
      couponCodes: ['SAVE-8', 'SHOP-2'],
    })

    expect(original).toEqual({
      addressId: 3,
      province: 'CN-ZJ',
      couponCodes: ['SAVE-8', 'SHOP-2'],
    })
    expect(getIdempotencyIntent('cart:checkout', equivalent).key).toBe(
      getIdempotencyIntent('cart:checkout', original).key,
    )
    expect(
      getIdempotencyIntent(
        'cart:checkout',
        normalizeCartCheckoutIntent({ ...original, addressId: 9 }),
      ).key,
    ).not.toBe(getIdempotencyIntent('cart:checkout', original).key)
  })

  it('sums store and platform discount allocations independently', () => {
    expect(
      checkoutDiscountTotals([
        { storeDiscountAmount: '3.25', platformDiscountAmount: '5.00' },
        { storeDiscountAmount: 2.75, platformDiscountAmount: '4.50' },
      ]),
    ).toEqual({ store: 6, platform: 9.5 })
  })

  it('uses persisted order ids and falls back to unique formal suborder ids', () => {
    expect(
      checkoutOrderIds({
        orderIds: [501, 502, 501],
        subOrders: [{ formalOrderId: 999 }],
      }),
    ).toEqual([501, 502])
    expect(
      checkoutOrderIds({
        orderIds: [],
        subOrders: [{ formalOrderId: 701 }, { formalOrderId: 702 }, { formalOrderId: 701 }],
      }),
    ).toEqual([701, 702])
  })

  it('preserves the selected sku and quantity in a direct checkout intent', () => {
    expect(
      buildDirectCheckoutIntent(
        {
          skuId: 1002,
          shopId: 9,
          quantity: 3,
        },
        22,
      ),
    ).toEqual({
      skuId: 1002,
      shopId: 9,
      quantity: 3,
      addressId: 22,
      couponCodes: [],
    })
  })
})

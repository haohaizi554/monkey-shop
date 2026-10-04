import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => vi.fn().mockResolvedValue({}))

vi.mock('@/api/http', () => ({
  request: requestMock,
}))

import { addCartItem, removeCartItem, selectCartItem, updateCartItem } from '@/api/cart'
import { getCatalogSpu } from '@/api/catalog'
import { logisticsForOrder } from '@/api/logistics'
import { createOrder, createShipment, myOrder, orderReviews, reviewOrder } from '@/api/orders'
import { adminPaymentForOrder, createPayment, paymentForOrder } from '@/api/payments'
import { inventoryStocks, reserveInventory } from '@/api/inventory'
import { addCollection, adminMembershipDashboard, removeCollection } from '@/api/membership'
import { claimCoupon, createSeckillOrder, joinGroupBuy, quoteMarketingPrice } from '@/api/marketing'
import { assessRisk, resolveRiskReview, riskReviews } from '@/api/risk'
import { searchProducts } from '@/api/search'
import { recordTrackingEvent, trackingProductProfile } from '@/api/tracking'
import {
  generateTenantBill,
  renewTenant,
  requestTenantExport,
  tenantExportDownloadUri,
  tenantConfigs,
} from '@/api/tenant'

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
    await createOrder(SNOWFLAKE_ID, SNOWFLAKE_ID, 'snowflake-order-key')
    await createPayment({ orderId: SNOWFLAKE_ID, method: 'WECHAT' }, 'snowflake-payment-key')
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
    await createPayment({ orderId: 101, method: 'ALIPAY' }, 'numeric-payment-key')

    expect(requestMock).toHaveBeenNthCalledWith(1, { url: '/orders/101' })
    expect(requestMock).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ data: { orderId: 101, method: 'ALIPAY' } }),
    )
  })

  it('keeps string IDs exact across catalog, cart, inventory, membership, marketing, risk, search, tracking, and tenant calls', async () => {
    await getCatalogSpu(SNOWFLAKE_ID)
    await addCartItem({ skuId: SNOWFLAKE_ID, shopId: SNOWFLAKE_ID, quantity: 2, selected: true })
    await updateCartItem(SNOWFLAKE_ID, { quantity: 3 })
    await selectCartItem(SNOWFLAKE_ID, { selected: false })
    await removeCartItem(SNOWFLAKE_ID)
    await inventoryStocks(SNOWFLAKE_ID)
    await reserveInventory({
      skuId: SNOWFLAKE_ID,
      warehouseId: SNOWFLAKE_ID,
      orderId: SNOWFLAKE_ID,
      quantity: 1,
      reservationKey: 'reservation-snowflake',
    })
    await adminMembershipDashboard(SNOWFLAKE_ID)
    await addCollection({ productId: SNOWFLAKE_ID, targetPrice: '99.00' })
    await removeCollection(SNOWFLAKE_ID)
    await claimCoupon({ couponId: SNOWFLAKE_ID, idempotencyKey: 'coupon-snowflake' })
    await createSeckillOrder({
      activityId: SNOWFLAKE_ID,
      orderId: SNOWFLAKE_ID,
      quantity: 1,
      idempotencyKey: 'seckill-snowflake',
    })
    await joinGroupBuy({
      activityId: SNOWFLAKE_ID,
      teamId: SNOWFLAKE_ID,
      idempotencyKey: 'group-snowflake',
    })
    await quoteMarketingPrice({
      orderAmount: '100.00',
      userId: SNOWFLAKE_ID,
      categoryId: SNOWFLAKE_ID,
      shopId: SNOWFLAKE_ID,
      couponCodes: [],
    })
    await assessRisk({
      productId: SNOWFLAKE_ID,
      orderId: SNOWFLAKE_ID,
      seckillActivityId: SNOWFLAKE_ID,
      sellerUserId: SNOWFLAKE_ID,
    })
    await riskReviews()
    await resolveRiskReview(SNOWFLAKE_ID, { status: 'APPROVED' })
    await searchProducts({ keyword: 'monkey', categoryId: SNOWFLAKE_ID })
    await recordTrackingEvent({ eventType: 'PRODUCT_VIEW', productId: SNOWFLAKE_ID })
    await trackingProductProfile(SNOWFLAKE_ID)
    await renewTenant(SNOWFLAKE_ID, { months: 1 })
    await tenantConfigs(SNOWFLAKE_ID)
    await generateTenantBill(SNOWFLAKE_ID, { billingMonth: '2026-08' })
    await requestTenantExport(SNOWFLAKE_ID, { exportType: 'FULL' })

    expect(requestMock.mock.calls.map(([config]) => config.url)).toEqual([
      `/catalog/spus/${SNOWFLAKE_ID}`,
      '/cart/items',
      `/cart/items/${SNOWFLAKE_ID}`,
      `/cart/items/${SNOWFLAKE_ID}/select`,
      `/cart/items/${SNOWFLAKE_ID}`,
      `/inventory/skus/${SNOWFLAKE_ID}/stocks`,
      '/inventory/reservations',
      `/membership/admin/${SNOWFLAKE_ID}/dashboard`,
      '/membership/collections',
      `/membership/collections/${SNOWFLAKE_ID}`,
      '/marketing/coupons/claim',
      '/marketing/seckill-orders',
      '/marketing/group-buy/join',
      '/marketing/price/quote',
      '/risk/assess',
      '/risk/reviews',
      `/risk/reviews/${SNOWFLAKE_ID}/resolve`,
      '/search/products',
      '/tracking/events',
      `/tracking/products/${SNOWFLAKE_ID}`,
      `/tenants/${SNOWFLAKE_ID}/renew`,
      `/tenants/${SNOWFLAKE_ID}/configs`,
      `/tenants/${SNOWFLAKE_ID}/bills`,
      `/tenants/${SNOWFLAKE_ID}/exports`,
    ])
    expect(requestMock.mock.calls[1][0]).toEqual(
      expect.objectContaining({
        data: { skuId: SNOWFLAKE_ID, shopId: SNOWFLAKE_ID, quantity: 2, selected: true },
      }),
    )
    expect(requestMock.mock.calls[6][0]).toEqual(
      expect.objectContaining({
        data: expect.objectContaining({
          skuId: SNOWFLAKE_ID,
          warehouseId: SNOWFLAKE_ID,
          orderId: SNOWFLAKE_ID,
          quantity: 1,
        }),
      }),
    )
    expect(requestMock.mock.calls[17][0]).toEqual(
      expect.objectContaining({ params: { keyword: 'monkey', categoryId: SNOWFLAKE_ID } }),
    )
    expect(requestMock.mock.calls[18][0]).toEqual(
      expect.objectContaining({ data: { eventType: 'PRODUCT_VIEW', productId: SNOWFLAKE_ID } }),
    )
  })

  it('keeps unsafe tenant export IDs exact in the artifact download route', () => {
    const expected = `/api/v1/tenants/${SNOWFLAKE_ID}/exports/${SNOWFLAKE_ID}/artifact`
    const job = {
      id: SNOWFLAKE_ID,
      tenantId: SNOWFLAKE_ID,
      exportType: 'FULL',
      status: 'SUCCEEDED' as const,
      artifactAvailable: true,
      artifactDownloadUri: expected,
      requestedBy: SNOWFLAKE_ID,
      requestedAt: '2026-08-28T00:00:00Z',
      version: 1,
    }

    expect(tenantExportDownloadUri(job)).toBe(expected)
    expect(tenantExportDownloadUri({ ...job, id: Number(SNOWFLAKE_ID) })).toBeUndefined()
  })
})

import { beforeEach, describe, expect, it } from 'vitest'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'

describe('idempotency intents', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('reuses one key for the same intent and normalized payload until success', () => {
    const first = getIdempotencyIntent('payment:create', {
      orderId: 42,
      metadata: { channel: 'WECHAT', campaign: 'summer' },
      omitted: undefined,
    })
    const retry = getIdempotencyIntent(' payment:create ', {
      metadata: { campaign: 'summer', channel: 'WECHAT' },
      orderId: 42,
    })

    expect(retry.key).toBe(first.key)
    expect(sessionStorage.length).toBe(1)

    first.complete()

    const nextIntent = getIdempotencyIntent('payment:create', {
      metadata: { channel: 'WECHAT', campaign: 'summer' },
      orderId: 42,
    })
    expect(nextIntent.key).not.toBe(first.key)
  })

  it('keeps different business intents and payloads isolated', () => {
    const payment = getIdempotencyIntent('payment:create', { orderId: 42 })
    const refund = getIdempotencyIntent('payment:refund', { orderId: 42 })
    const anotherPayment = getIdempotencyIntent('payment:create', { orderId: 43 })
    const reorderedItems = getIdempotencyIntent('order:create', { skuIds: [2, 1] })
    const originalItems = getIdempotencyIntent('order:create', { skuIds: [1, 2] })

    expect(new Set([payment.key, refund.key, anotherPayment.key]).size).toBe(3)
    expect(reorderedItems.key).not.toBe(originalItems.key)
  })

  it('does not let an old completed handle clear a newer intent', () => {
    const oldIntent = getIdempotencyIntent('shipment:create', { orderId: 7 })
    oldIntent.complete()
    const currentIntent = getIdempotencyIntent('shipment:create', { orderId: 7 })

    oldIntent.complete()

    expect(getIdempotencyIntent('shipment:create', { orderId: 7 }).key).toBe(currentIntent.key)
  })
})

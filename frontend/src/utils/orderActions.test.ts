import { describe, expect, it } from 'vitest'
import { consumerOrderActions, normalizeConsumerOrderStatus } from '@/utils/orderActions'

describe('consumer order actions', () => {
  it('normalizes stored labels and API aliases to one status vocabulary', () => {
    expect(normalizeConsumerOrderStatus('PENDING_PAYMENT')).toBe('PAYMENT_PENDING')
    expect(normalizeConsumerOrderStatus('\u5f85\u652f\u4ed8')).toBe('PAYMENT_PENDING')
    expect(normalizeConsumerOrderStatus('RETURN_APPROVED')).toBe('WAITING_RETURN_SHIPMENT')
  })

  it('exposes only legal consumer actions for each order state', () => {
    expect(consumerOrderActions('PENDING_PAYMENT')).toEqual(['pay'])
    expect(consumerOrderActions('PAID')).toEqual(['logistics'])
    expect(consumerOrderActions('SHIPPED')).toEqual(['receive', 'logistics'])
    expect(consumerOrderActions('PARTIALLY_RECEIVED')).toEqual(['receive', 'logistics'])
    expect(consumerOrderActions('COMPLETED')).toEqual([
      'requestReturn',
      'review',
      'logistics',
      'hide',
    ])
    expect(consumerOrderActions('RETURN_APPROVED')).toEqual(['shipReturn', 'logistics'])
    expect(consumerOrderActions('REFUNDED')).toEqual(['hide'])
  })

  it('never exposes merchant-only fulfillment decisions', () => {
    for (const status of [
      'PENDING_PAYMENT',
      'PAID',
      'SHIPPED',
      'COMPLETED',
      'RETURN_REQUESTED',
      'RETURN_APPROVED',
      'RETURN_SHIPPING',
      'REFUNDED',
    ]) {
      expect(consumerOrderActions(status)).not.toContain('ship')
      expect(consumerOrderActions(status)).not.toContain('approveReturn')
      expect(consumerOrderActions(status)).not.toContain('confirmReturn')
    }
  })
})

import { describe, expect, it } from 'vitest'
import { adminOrderActions, normalizeAdminOrderStatus } from './adminOrderActions'

describe('admin order actions', () => {
  it('offers fulfillment only for paid and partially shipped orders', () => {
    expect(adminOrderActions('PAID')).toContain('ship')
    expect(adminOrderActions('PARTIALLY_SHIPPED')).toContain('ship')
    expect(adminOrderActions('PENDING_PAYMENT')).not.toContain('ship')
    expect(adminOrderActions('COMPLETED')).not.toContain('ship')
  })

  it('scopes return decisions to their valid states', () => {
    expect(adminOrderActions('RETURN_REQUESTED')).toContain('approveReturn')
    expect(adminOrderActions('RETURN_SHIPPING')).toContain('refundReturn')
    expect(adminOrderActions('WAITING_RETURN_SHIPMENT')).not.toContain('refundReturn')
  })

  it('normalizes stored labels without ever producing callback simulation actions', () => {
    expect(normalizeAdminOrderStatus('\u5df2\u652f\u4ed8')).toBe('PAID')
    expect(normalizeAdminOrderStatus('not-a-status')).toBe('UNKNOWN')
    expect([
      ...adminOrderActions('PAID'),
      ...adminOrderActions('RETURN_REQUESTED'),
      ...adminOrderActions('RETURN_SHIPPING'),
    ]).not.toEqual(expect.arrayContaining(['callback', 'webhook']))
  })
})

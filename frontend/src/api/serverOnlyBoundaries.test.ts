import { describe, expect, it } from 'vitest'
import * as inventoryApi from './inventory'
import * as logisticsApi from './logistics'
import * as membershipApi from './membership'
import * as trackingApi from './tracking'

describe('browser API boundaries', () => {
  it('does not export machine callbacks or orchestration-only mutations', () => {
    expect(logisticsApi).not.toHaveProperty('pushWebhook')
    expect(logisticsApi).not.toHaveProperty('createShipment')
    expect(inventoryApi).not.toHaveProperty('deductInventory')
    expect(inventoryApi).not.toHaveProperty('compensateInventory')
  })

  it('uses target-aware member administration and avoids arbitrary profile lookup', () => {
    expect(membershipApi).not.toHaveProperty('earnPoints')
    expect(membershipApi).not.toHaveProperty('changeLevel')
    expect(membershipApi).toHaveProperty('adminEarnPoints')
    expect(membershipApi).toHaveProperty('adminChangeLevel')
    expect(trackingApi).not.toHaveProperty('trackingUserProfile')
  })
})

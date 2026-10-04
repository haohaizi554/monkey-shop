import { beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest'
import { recordTrackingEvent } from '@/api/tracking'
import { trackEvent } from '@/TrackingSdk'
import type { ClientTrackingEventType, TrackingEventRequest } from '@/types'

const requestMock = vi.hoisted(() => vi.fn().mockResolvedValue({}))

vi.mock('@/api/http', () => ({
  request: requestMock,
}))

describe('public tracking event contract', () => {
  beforeEach(() => {
    requestMock.mockClear()
  })

  it('restricts public submissions to client-originated event types', () => {
    expectTypeOf<TrackingEventRequest['eventType']>().toEqualTypeOf<ClientTrackingEventType>()
    expectTypeOf<
      Parameters<typeof recordTrackingEvent>[0]['eventType']
    >().toEqualTypeOf<ClientTrackingEventType>()
    expectTypeOf<Parameters<typeof trackEvent>[0]>().toEqualTypeOf<ClientTrackingEventType>()
  })

  it('forwards an allowed client event through the public request boundary', async () => {
    await recordTrackingEvent({ eventType: 'ADD_TO_CART' })

    expect(requestMock).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'POST',
        url: '/tracking/events',
        data: expect.objectContaining({ eventType: 'ADD_TO_CART' }),
      }),
    )
  })

  it('does not submit an authoritative event even when called through untyped JavaScript', async () => {
    await expect(
      recordTrackingEvent({ eventType: 'ORDER_CREATED' } as unknown as TrackingEventRequest),
    ).rejects.toThrow('Authoritative commerce events')

    expect(requestMock).not.toHaveBeenCalled()
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { trackEvent } from '@/TrackingSdk'
import { getUiErrorReference, reportUiError } from '@/utils/reportUiError'

vi.mock('@/TrackingSdk', () => ({ trackEvent: vi.fn() }))

describe('reportUiError', () => {
  beforeEach(() => {
    vi.mocked(trackEvent).mockClear()
  })

  it('uses one support reference as the telemetry trace id', () => {
    const error = new Error('render failed')
    const reference = getUiErrorReference(error)

    expect(reportUiError(error, 'render function')).toBe(reference)
    expect(trackEvent).toHaveBeenCalledWith(
      'UI_ERROR',
      expect.objectContaining({
        traceId: reference,
        attributes: expect.objectContaining({ errorName: 'Error', info: 'render function' }),
      }),
    )
  })

  it('reports the same error object only once', () => {
    const error = new Error('single event')

    reportUiError(error, 'first')
    reportUiError(error, 'second')

    expect(trackEvent).toHaveBeenCalledOnce()
  })

  it('uses the boundary support reference for primitive render errors', () => {
    const reference = getUiErrorReference('primitive render failure')

    expect(reportUiError('primitive render failure', 'render function', reference)).toBe(reference)
    expect(trackEvent).toHaveBeenCalledWith(
      'UI_ERROR',
      expect.objectContaining({ traceId: reference }),
    )
  })
})

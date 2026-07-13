import { describe, expect, it, vi } from 'vitest'
import { parseRetryAfter, useRetryCountdown } from '@/composables/useRetryCountdown'

describe('parseRetryAfter', () => {
  it('uses Retry-After delta seconds before problem metadata', () => {
    expect(
      parseRetryAfter(
        {
          headers: { 'retry-after': '9' },
          data: { retryAfterSeconds: 30, retryAt: '2099-01-01T00:00:00Z' },
        },
        1_000,
      ),
    ).toBe(10_000)
  })

  it('accepts an HTTP date and falls back to ProblemDetail retry metadata', () => {
    expect(
      parseRetryAfter({ headers: { 'Retry-After': 'Thu, 01 Jan 1970 00:00:12 GMT' } }, 1_000),
    ).toBe(12_000)
    expect(parseRetryAfter({ data: { retryAfterSeconds: 4 } }, 1_000)).toBe(5_000)
    expect(parseRetryAfter({ retryAt: '1970-01-01T00:00:08.000Z' }, 1_000)).toBe(8_000)
  })

  it('rejects malformed and already expired retry metadata', () => {
    expect(parseRetryAfter({ headers: { 'retry-after': 'later' } }, 1_000)).toBeNull()
    expect(parseRetryAfter({ retryAt: '1970-01-01T00:00:00.500Z' }, 1_000)).toBeNull()
  })
})

describe('useRetryCountdown', () => {
  it('counts down from an absolute deadline and stops at zero', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(1_000)
    const countdown = useRetryCountdown()

    expect(countdown.start({ data: { retryAfterSeconds: 2 } })).toBe(3_000)
    expect(countdown.retryAt.value).toBe(3_000)
    expect(countdown.remainingSeconds.value).toBe(2)
    expect(countdown.isActive.value).toBe(true)

    await vi.advanceTimersByTimeAsync(1_000)
    expect(countdown.remainingSeconds.value).toBe(1)

    await vi.advanceTimersByTimeAsync(1_000)
    expect(countdown.remainingSeconds.value).toBe(0)
    expect(countdown.isActive.value).toBe(false)
    expect(vi.getTimerCount()).toBe(0)
  })
})

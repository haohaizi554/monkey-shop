import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { usePageVisibility } from '@/composables/usePageVisibility'

function setDocumentVisibility(value: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    value,
  })
}

describe('usePageVisibility', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    setDocumentVisibility('visible')
  })

  afterEach(() => {
    setDocumentVisibility('visible')
  })

  it('stops scheduled refresh while the page is hidden', () => {
    const controller = usePageVisibility()
    const callback = vi.fn()
    controller.start(callback, 5000)

    setDocumentVisibility('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    vi.advanceTimersByTime(15000)

    expect(callback).not.toHaveBeenCalled()
    controller.stop()
  })

  it('runs once on resume and restarts exactly one interval', async () => {
    const controller = usePageVisibility()
    const callback = vi.fn()
    controller.start(callback, 5000)
    setDocumentVisibility('hidden')
    document.dispatchEvent(new Event('visibilitychange'))

    setDocumentVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(0)
    expect(callback).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(10000)
    expect(callback).toHaveBeenCalledTimes(3)
    controller.stop()
  })

  it('does not overlap a slow callback', async () => {
    let resolve!: () => void
    const callback = vi.fn(() => new Promise<void>((done) => (resolve = done)))
    const controller = usePageVisibility()
    controller.start(callback, 1000)

    await vi.advanceTimersByTimeAsync(3000)
    expect(callback).toHaveBeenCalledOnce()
    resolve()
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(1000)
    expect(callback).toHaveBeenCalledTimes(2)
    controller.stop()
  })
})

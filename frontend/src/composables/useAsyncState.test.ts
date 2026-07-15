import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/http'
import {
  ASYNC_REQUEST_ERROR,
  ASYNC_TIMEOUT_ERROR,
  type AsyncErrorKey,
  useAsyncState,
} from './useAsyncState'

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, reject, resolve }
}

describe('useAsyncState', () => {
  it('moves an initial request from loading to success', async () => {
    const state = useAsyncState<string>()
    const request = deferred<string>()

    const pending = state.load(() => request.promise)

    expect(state.status.value).toBe('loading')
    request.resolve('ready')
    await expect(pending).resolves.toBe('ready')
    expect(state.data.value).toBe('ready')
    expect(state.status.value).toBe('success')
    expect(state.error.value).toBeNull()
  })

  it('uses the empty predicate for accepted results', async () => {
    const state = useAsyncState<string[]>()

    await state.load(async () => [], { isEmpty: (items) => items.length === 0 })

    expect(state.status.value).toBe('empty')
  })

  it('keeps valid data visible while updating', async () => {
    const state = useAsyncState<string>()
    await state.load(async () => 'first')
    const request = deferred<string>()

    const pending = state.load(() => request.promise)

    expect(state.status.value).toBe('updating')
    expect(state.data.value).toBe('first')
    expect(state.state.value).toEqual({ status: 'updating', data: 'first' })
    request.resolve('second')
    await pending
    expect(state.data.value).toBe('second')
    expect(state.status.value).toBe('success')
  })

  it('clears existing data when preservation is disabled', async () => {
    const state = useAsyncState<string>()
    await state.load(async () => 'first')
    const request = deferred<string>()

    const pending = state.load(() => request.promise, { preserveData: false })

    expect(state.status.value).toBe('loading')
    expect(state.data.value).toBeNull()
    request.resolve('second')
    await pending
    expect(state.data.value).toBe('second')
  })

  it('ignores an older request that resolves after the latest request', async () => {
    const state = useAsyncState<string>()
    const olderRequest = deferred<string>()
    const older = state.load(() => olderRequest.promise)

    await state.load(async () => 'latest')
    olderRequest.resolve('old')
    await older

    expect(state.data.value).toBe('latest')
    expect(state.status.value).toBe('success')
  })

  it('aborts the previous signal when a newer request starts', async () => {
    const state = useAsyncState<string>()
    const olderRequest = deferred<string>()
    let olderSignal: AbortSignal | undefined
    const older = state.load(({ signal }) => {
      olderSignal = signal
      return olderRequest.promise
    })

    await state.load(async () => 'latest')

    expect(olderSignal?.aborted).toBe(true)
    olderRequest.resolve('old')
    await older
    expect(state.data.value).toBe('latest')
  })

  it('ignores a superseded request that rejects late', async () => {
    const state = useAsyncState<string>()
    const olderRequest = deferred<string>()
    const older = state.load(() => olderRequest.promise)

    await state.load(async () => 'latest')
    olderRequest.reject(new Error('late failure'))

    await expect(older).resolves.toBeNull()
    expect(state.data.value).toBe('latest')
    expect(state.error.value).toBeNull()
  })

  it('cancel prevents a late result from writing state', async () => {
    const state = useAsyncState<string>()
    const request = deferred<string>()
    const pending = state.load(() => request.promise)

    state.cancel()
    request.resolve('late')
    await pending

    expect(state.data.value).toBeNull()
    expect(state.status.value).toBe('idle')
  })

  it('aborts and exposes a stable error key on timeout', async () => {
    vi.useFakeTimers()
    const state = useAsyncState<string>()
    let signal: AbortSignal | undefined
    const pending = state.load(
      ({ signal: requestSignal }) => {
        signal = requestSignal
        return new Promise<string>(() => undefined)
      },
      { timeoutMs: 25 },
    )

    await vi.advanceTimersByTimeAsync(25)

    await expect(pending).resolves.toBeNull()
    expect(signal?.aborted).toBe(true)
    expect(state.status.value).toBe('error')
    expect(state.error.value).toBe('common.requestTimeout')
  })

  it('consumes a loader rejection that arrives after timeout', async () => {
    vi.useFakeTimers()
    const state = useAsyncState<string>()
    const request = deferred<string>()
    const pending = state.load(() => request.promise, { timeoutMs: 10 })

    await vi.advanceTimersByTimeAsync(10)
    await expect(pending).resolves.toBeNull()
    request.reject(new Error('late timeout failure'))
    await Promise.resolve()

    expect(state.status.value).toBe('error')
    expect(state.error.value).toBe(ASYNC_TIMEOUT_ERROR)
  })

  it('does not expose an arbitrary loader error message', async () => {
    const state = useAsyncState<string>()

    await expect(
      state.load(async () => {
        throw new Error('Too many requests')
      }),
    ).resolves.toBeNull()

    expect(state.status.value).toBe('error')
    expect(state.error.value).toBe('common.requestFailed')
  })

  it('keeps safe API problem metadata without exposing the provider message', async () => {
    const state = useAsyncState<string>()
    const apiError = new ApiError('database connection details', 429, 'trace-429', 'RATE_LIMITED', {
      retryAfterSeconds: 12,
      retryAt: '2026-07-15T12:00:00Z',
      fieldErrors: [{ field: 'username', code: 'TAKEN', message: 'already used' }],
    })

    await state.load(async () => {
      throw apiError
    })

    expect(state.state.value).toEqual({
      status: 'error',
      problem: {
        messageKey: ASYNC_REQUEST_ERROR,
        status: 429,
        code: 'RATE_LIMITED',
        traceId: 'trace-429',
        retryAfterSeconds: 12,
        retryAt: '2026-07-15T12:00:00Z',
        fieldErrors: [{ field: 'username', code: 'TAKEN', message: 'already used' }],
      },
    })
    expect(JSON.stringify(state.problem.value)).not.toContain('database connection details')
  })

  it('normalizes a synchronous loader failure', async () => {
    const state = useAsyncState<string>()

    await expect(
      state.load(() => {
        throw new Error('Operation is not permitted')
      }),
    ).resolves.toBeNull()

    expect(state.status.value).toBe('error')
    expect(state.error.value).toBe('common.requestFailed')
  })

  it('reset aborts and invalidates the active request', async () => {
    const state = useAsyncState<string>()
    const request = deferred<string>()
    let signal: AbortSignal | undefined
    const pending = state.load(({ signal: requestSignal }) => {
      signal = requestSignal
      return request.promise
    })

    state.reset()
    request.resolve('late')
    await pending

    expect(signal?.aborted).toBe(true)
    expect(state.data.value).toBeNull()
    expect(state.status.value).toBe('idle')
  })

  it('setError aborts and invalidates the active request', async () => {
    const state = useAsyncState<string>()
    const request = deferred<string>()
    let signal: AbortSignal | undefined
    const pending = state.load(({ signal: requestSignal }) => {
      signal = requestSignal
      return request.promise
    })

    state.setError(ASYNC_TIMEOUT_ERROR)
    request.resolve('late')
    await pending

    expect(signal?.aborted).toBe(true)
    expect(state.data.value).toBeNull()
    expect(state.status.value).toBe('error')
    expect(state.error.value).toBe(ASYNC_TIMEOUT_ERROR)
  })

  it('normalizes an unsafe runtime value passed to setError', () => {
    const state = useAsyncState<string>()

    state.setError('Too many requests' as AsyncErrorKey)

    expect(state.error.value).toBe(ASYNC_REQUEST_ERROR)
  })
})

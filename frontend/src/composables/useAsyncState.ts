import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { ApiError } from '@/api/http'
import type { ApiProblem } from '@/types'

export const ASYNC_REQUEST_ERROR = 'common.requestFailed'
export const ASYNC_TIMEOUT_ERROR = 'common.requestTimeout'
export type AsyncErrorKey = typeof ASYNC_REQUEST_ERROR | typeof ASYNC_TIMEOUT_ERROR

export type AsyncStatus = 'idle' | 'loading' | 'updating' | 'success' | 'empty' | 'error'

export interface ResourceProblem extends ApiProblem {
  messageKey: AsyncErrorKey
}

export type ResourceState<T> =
  | { status: 'idle' | 'loading'; data?: undefined }
  | { status: 'success' | 'updating'; data: T }
  | { status: 'empty'; data?: undefined }
  | { status: 'error'; data?: T; problem: ResourceProblem }

export interface AsyncLoadContext {
  signal: AbortSignal
}

export interface AsyncLoadOptions<T> {
  isEmpty?: (value: T) => boolean
  preserveData?: boolean
  timeoutMs?: number
}

export type AsyncLoader<T> = (context: AsyncLoadContext) => Promise<T>

export interface AsyncState<T> {
  data: Ref<T | null>
  status: Ref<AsyncStatus>
  error: Ref<string | null>
  problem: Ref<ResourceProblem | null>
  state: ComputedRef<ResourceState<T>>
  isLoading: ComputedRef<boolean>
  isEmpty: ComputedRef<boolean>
  isError: ComputedRef<boolean>
  isSuccess: ComputedRef<boolean>
  load: (loader: AsyncLoader<T>, options?: AsyncLoadOptions<T>) => Promise<T | null>
  cancel: () => void
  reset: () => void
  setError: (message: AsyncErrorKey) => void
}

class AsyncTimeoutError extends Error {}

export function useAsyncState<T>(defaults: AsyncLoadOptions<T> = {}): AsyncState<T> {
  const data = ref<T | null>(null) as Ref<T | null>
  const status = ref<AsyncStatus>('idle')
  const error = ref<string | null>(null)
  const problem = ref<ResourceProblem | null>(null)
  const defaultTimeoutMs = defaults.timeoutMs ?? 15000

  let activeRequestId = 0
  let controller: AbortController | null = null
  let settledStatus: 'idle' | 'success' | 'empty' = 'idle'

  const isLoading = computed(() => status.value === 'loading' || status.value === 'updating')
  const isEmpty = computed(() => status.value === 'empty')
  const isError = computed(() => status.value === 'error')
  const isSuccess = computed(() => status.value === 'success')
  const state = computed<ResourceState<T>>(() => {
    if (status.value === 'success' || status.value === 'updating') {
      return { status: status.value, data: data.value as T }
    }
    if (status.value === 'error') {
      return {
        status: 'error',
        ...(data.value === null ? {} : { data: data.value }),
        problem: problem.value ?? { messageKey: ASYNC_REQUEST_ERROR },
      }
    }
    return { status: status.value }
  })

  function invalidateActiveRequest() {
    activeRequestId += 1
    controller?.abort()
    controller = null
  }

  function restoreSettledState() {
    error.value = null
    problem.value = null
    if (data.value === null) {
      status.value = 'idle'
      return
    }
    status.value = settledStatus === 'empty' ? 'empty' : 'success'
  }

  async function load(
    loader: AsyncLoader<T>,
    options: AsyncLoadOptions<T> = {},
  ): Promise<T | null> {
    const requestId = ++activeRequestId
    controller?.abort()
    const requestController = new AbortController()
    controller = requestController

    const preserveData = options.preserveData ?? defaults.preserveData ?? true
    const emptyCheck = options.isEmpty ?? defaults.isEmpty
    const timeoutMs = options.timeoutMs ?? defaultTimeoutMs

    if (!preserveData) {
      data.value = null
      settledStatus = 'idle'
    }

    status.value = data.value !== null && preserveData ? 'updating' : 'loading'
    error.value = null
    problem.value = null

    let timer: ReturnType<typeof setTimeout> | undefined
    let didTimeout = false
    const loaderPromise = Promise.resolve().then(() => loader({ signal: requestController.signal }))
    const timeoutPromise = new Promise<never>((_, reject) => {
      if (timeoutMs <= 0) {
        return
      }
      timer = setTimeout(() => {
        didTimeout = true
        requestController.abort()
        reject(new AsyncTimeoutError())
      }, timeoutMs)
    })

    try {
      const result =
        timeoutMs > 0 ? await Promise.race([loaderPromise, timeoutPromise]) : await loaderPromise
      if (requestId !== activeRequestId) {
        return null
      }

      data.value = result
      settledStatus = emptyCheck?.(result) ? 'empty' : 'success'
      status.value = settledStatus
      return result
    } catch (caught) {
      if (requestId !== activeRequestId) {
        return null
      }

      status.value = 'error'
      const messageKey =
        didTimeout || caught instanceof AsyncTimeoutError
          ? ASYNC_TIMEOUT_ERROR
          : ASYNC_REQUEST_ERROR
      error.value = messageKey
      problem.value = {
        messageKey,
        ...(caught instanceof ApiError
          ? {
              status: caught.status,
              code: caught.code,
              traceId: caught.traceId,
              retryAfterSeconds: caught.retryAfterSeconds,
              retryAt: caught.retryAt,
              fieldErrors: caught.fieldErrors ? [...caught.fieldErrors] : undefined,
            }
          : {}),
      }
      return null
    } finally {
      if (timer !== undefined) {
        clearTimeout(timer)
      }
      if (requestId === activeRequestId && controller === requestController) {
        controller = null
      }
    }
  }

  function cancel() {
    if (controller === null) {
      return
    }
    invalidateActiveRequest()
    restoreSettledState()
  }

  function reset() {
    invalidateActiveRequest()
    data.value = null
    settledStatus = 'idle'
    status.value = 'idle'
    error.value = null
    problem.value = null
  }

  function setError(message: AsyncErrorKey) {
    invalidateActiveRequest()
    status.value = 'error'
    const messageKey = message === ASYNC_TIMEOUT_ERROR ? ASYNC_TIMEOUT_ERROR : ASYNC_REQUEST_ERROR
    error.value = messageKey
    problem.value = { messageKey }
  }

  return {
    data,
    status,
    error,
    problem,
    state,
    isLoading,
    isEmpty,
    isError,
    isSuccess,
    load,
    cancel,
    reset,
    setError,
  }
}

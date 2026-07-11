import { computed, ref, type ComputedRef, type Ref } from 'vue'

export const ASYNC_REQUEST_ERROR = 'common.requestFailed'
export const ASYNC_TIMEOUT_ERROR = 'common.requestTimeout'

export type AsyncStatus = 'idle' | 'loading' | 'updating' | 'success' | 'empty' | 'error'

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
  isLoading: ComputedRef<boolean>
  isEmpty: ComputedRef<boolean>
  isError: ComputedRef<boolean>
  isSuccess: ComputedRef<boolean>
  load: (loader: AsyncLoader<T>, options?: AsyncLoadOptions<T>) => Promise<T | null>
  cancel: () => void
  reset: () => void
  setError: (message: string) => void
}

class AsyncTimeoutError extends Error {}

export function useAsyncState<T>(defaults: AsyncLoadOptions<T> = {}): AsyncState<T> {
  const data = ref<T | null>(null) as Ref<T | null>
  const status = ref<AsyncStatus>('idle')
  const error = ref<string | null>(null)
  const defaultTimeoutMs = defaults.timeoutMs ?? 15000

  let activeRequestId = 0
  let controller: AbortController | null = null
  let settledStatus: 'idle' | 'success' | 'empty' = 'idle'

  const isLoading = computed(() => status.value === 'loading' || status.value === 'updating')
  const isEmpty = computed(() => status.value === 'empty')
  const isError = computed(() => status.value === 'error')
  const isSuccess = computed(() => status.value === 'success')

  function invalidateActiveRequest() {
    activeRequestId += 1
    controller?.abort()
    controller = null
  }

  function restoreSettledState() {
    error.value = null
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
      error.value =
        didTimeout || caught instanceof AsyncTimeoutError
          ? ASYNC_TIMEOUT_ERROR
          : ASYNC_REQUEST_ERROR
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
  }

  function setError(message: string) {
    invalidateActiveRequest()
    status.value = 'error'
    error.value = message
  }

  return {
    data,
    status,
    error,
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

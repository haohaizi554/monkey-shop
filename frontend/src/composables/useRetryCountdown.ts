import {
  computed,
  getCurrentScope,
  onScopeDispose,
  readonly,
  ref,
  type ComputedRef,
  type Ref,
} from 'vue'

type UnknownRecord = Record<string, unknown>

export interface RetryCountdownController {
  retryAt: Readonly<Ref<number | null>>
  remainingSeconds: ComputedRef<number>
  isActive: ComputedRef<boolean>
  start: (source: unknown) => number | null
  clear: () => void
}

function asRecord(value: unknown): UnknownRecord | null {
  return typeof value === 'object' && value !== null ? (value as UnknownRecord) : null
}

function headerValue(headers: unknown, name: string): string | null {
  const record = asRecord(headers)
  if (!record) {
    return null
  }

  if (typeof record.get === 'function') {
    const value = record.get.call(headers, name)
    if (value !== null && value !== undefined) {
      return String(value)
    }
  }

  const entry = Object.entries(record).find(([key]) => key.toLowerCase() === name.toLowerCase())
  const value = entry?.[1]
  if (Array.isArray(value)) {
    return value.length > 0 ? String(value[0]) : null
  }
  return value === null || value === undefined ? null : String(value)
}

function futureEpoch(value: unknown, now: number): number | null {
  const parsed =
    typeof value === 'number'
      ? value
      : typeof value === 'string' && value.trim().length > 0
        ? Date.parse(value)
        : Number.NaN
  return Number.isFinite(parsed) && parsed > now ? parsed : null
}

function retryHeaderEpoch(value: string | null, now: number): number | null {
  if (!value) {
    return null
  }
  const normalized = value.trim()
  if (/^\d+$/.test(normalized)) {
    return futureEpoch(now + Number(normalized) * 1_000, now)
  }
  return futureEpoch(normalized, now)
}

export function parseRetryAfter(source: unknown, now: number = Date.now()): number | null {
  const root = asRecord(source)
  if (!root || !Number.isFinite(now)) {
    return null
  }
  const response = asRecord(root.response)
  const envelope = response ?? root

  const headerEpoch = retryHeaderEpoch(headerValue(envelope.headers, 'retry-after'), now)
  if (headerEpoch !== null) {
    return headerEpoch
  }

  const data = asRecord(envelope.data)
  const problem = data ?? root
  const absoluteEpoch = futureEpoch(problem.retryAt, now)
  if (absoluteEpoch !== null) {
    return absoluteEpoch
  }

  const retryAfterSeconds = Number(problem.retryAfterSeconds)
  if (!Number.isFinite(retryAfterSeconds) || retryAfterSeconds <= 0) {
    return null
  }
  return now + Math.ceil(retryAfterSeconds * 1_000)
}

export function useRetryCountdown(): RetryCountdownController {
  const retryAt = ref<number | null>(null)
  const currentTime = ref(Date.now())
  let timer: ReturnType<typeof setInterval> | undefined

  const remainingSeconds = computed(() => {
    if (retryAt.value === null) {
      return 0
    }
    return Math.max(0, Math.ceil((retryAt.value - currentTime.value) / 1_000))
  })
  const isActive = computed(() => remainingSeconds.value > 0)

  function clearTimer(): void {
    if (timer !== undefined) {
      clearInterval(timer)
      timer = undefined
    }
  }

  function tick(): void {
    currentTime.value = Date.now()
    if (retryAt.value === null || retryAt.value <= currentTime.value) {
      clearTimer()
    }
  }

  function start(source: unknown): number | null {
    const now = Date.now()
    const deadline = parseRetryAfter(source, now)
    clearTimer()
    currentTime.value = now
    retryAt.value = deadline
    if (deadline !== null) {
      timer = setInterval(tick, 1_000)
    }
    return deadline
  }

  function clear(): void {
    clearTimer()
    retryAt.value = null
    currentTime.value = Date.now()
  }

  if (getCurrentScope()) {
    onScopeDispose(clear)
  }

  return {
    retryAt: readonly(retryAt),
    remainingSeconds,
    isActive,
    start,
    clear,
  }
}

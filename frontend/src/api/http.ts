import axios, {
  AxiosError,
  AxiosHeaders,
  type AxiosRequestConfig,
  type AxiosResponseTransformer,
  type InternalAxiosRequestConfig,
} from 'axios'
import { parseApiJson } from '@/api/safeJson'
import type { ApiProblem, ApiResult, FieldViolation } from '@/types'
import { csrfHeader } from '@/utils/csrf'

const unsafeMethods = new Set(['post', 'put', 'patch', 'delete'])
const traceIdHeader = 'X-Trace-Id'
const idempotencyKeyHeader = 'Idempotency-Key'
const deviceFingerprintHeader = 'X-Device-Fingerprint'
const refreshLockName = 'monkeyshop:auth-refresh:v1'
const refreshGenerationStorageKey = 'monkeyshop:auth-refresh-generation:v1'
const refreshLeaseStorageKey = 'monkeyshop:auth-refresh-lease:v1'
const refreshLeaseDurationMs = 30_000
const refreshLeasePollMs = 25
let fallbackTraceCounter = 0
let pageDeviceFingerprint: string | undefined
let sessionRefreshPromise: Promise<void> | null = null

interface RetriableConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

interface RefreshLease {
  owner: string
  expiresAt: number
}

interface LockManagerLike {
  request<T>(name: string, callback: () => Promise<T>): Promise<T>
}

function normalizeResponseHeaders(headers: unknown): AxiosHeaders | undefined {
  if (!headers || typeof headers !== 'object') {
    return undefined
  }
  if (headers instanceof AxiosHeaders) {
    return new AxiosHeaders(headers)
  }

  const normalized = new AxiosHeaders()
  for (const [name, value] of Object.entries(headers)) {
    if (value !== null && value !== undefined) {
      normalized.set(name, String(value))
    }
  }
  return normalized
}

export class ApiError extends Error {
  readonly status?: number
  readonly traceId?: string
  readonly code?: string
  readonly fieldErrors?: readonly FieldViolation[]
  readonly retryAfterSeconds?: number
  readonly retryAt?: string
  readonly headers?: AxiosHeaders

  constructor(
    message: string,
    status?: number,
    traceId?: string,
    code?: string,
    problem?: ApiProblem,
    headers?: AxiosHeaders,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.traceId = traceId
    this.code = code
    this.fieldErrors = problem?.fieldErrors ? [...problem.fieldErrors] : undefined
    this.retryAfterSeconds = problem?.retryAfterSeconds
    this.retryAt = problem?.retryAt
    this.headers = headers
  }
}

export const http = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
  transformResponse: [
    ((data: unknown, headers: AxiosHeaders) => {
      if (typeof data !== 'string') {
        return data
      }
      const normalized = data.trim()
      if (!normalized) {
        return data
      }
      const contentType = String(headers?.get('content-type') || '')
      if (!contentType.toLowerCase().includes('json') && !/^[{[]/.test(normalized)) {
        return data
      }
      try {
        return parseApiJson(normalized)
      } catch {
        return data
      }
    }) as AxiosResponseTransformer,
  ],
  headers: {
    Accept: 'application/json',
  },
})

function createTraceId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const bytes = crypto.getRandomValues(new Uint8Array(16))
    return `web-${Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')}`
  }
  fallbackTraceCounter += 1
  return `web-${Date.now().toString(36)}-${fallbackTraceCounter.toString(36)}`
}

export function browserDeviceFingerprint(): string | undefined {
  if (typeof window === 'undefined') {
    return undefined
  }
  if (!pageDeviceFingerprint) {
    pageDeviceFingerprint = createTraceId()
  }
  return pageDeviceFingerprint
}

function currentLocale(): 'en' | 'zh' {
  if (typeof localStorage === 'undefined') {
    return 'zh'
  }
  return localStorage.getItem('monkeyshop-locale') === 'en' ? 'en' : 'zh'
}

function normalizeCode(code?: string): string {
  return (code || '')
    .trim()
    .toUpperCase()
    .replace(/[\s-]+/g, '_')
}

function localized(en: string, zh: string): string {
  return currentLocale() === 'en' ? en : zh
}

function friendlyMessage(message: string | undefined, status?: number, code?: string): string {
  const errorCode = normalizeCode(code)
  const raw = (message || '').trim()
  const normalized = raw.toLowerCase()
  const authChallengeMessages = new Set([
    'admin mfa required',
    'admin mfa invalid',
    'captcha required',
    'captcha incorrect',
  ])

  if (authChallengeMessages.has(normalized)) {
    return normalized
  }

  if (
    errorCode === 'RATE_LIMIT' ||
    errorCode === 'TOO_MANY_REQUESTS' ||
    status === 429 ||
    normalized.includes('too many requests') ||
    normalized.includes('too many attempts') ||
    normalized.includes('temporarily locked')
  ) {
    return localized(
      'Too many attempts. Please wait a moment and try again.',
      '\u64cd\u4f5c\u592a\u9891\u7e41\u4e86\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002',
    )
  }
  if (errorCode === 'FORBIDDEN' || status === 403 || normalized === 'operation is not permitted') {
    if (normalized.includes('password change required')) {
      return localized(
        'Your password has expired. Please update it before continuing.',
        '\u5bc6\u7801\u5df2\u8fc7\u671f\uff0c\u8bf7\u5148\u4fee\u6539\u5bc6\u7801\u540e\u518d\u7ee7\u7eed\u3002',
      )
    }
    return localized(
      'You do not have permission to perform this action.',
      '\u5f53\u524d\u8d26\u53f7\u6ca1\u6709\u6743\u9650\u6267\u884c\u8fd9\u4e2a\u64cd\u4f5c\u3002',
    )
  }
  if (normalized.includes('username or password')) {
    return localized(
      'Username or password is incorrect.',
      '\u7528\u6237\u540d\u6216\u5bc6\u7801\u4e0d\u6b63\u786e\u3002',
    )
  }
  if (normalized.includes('phone verification failed')) {
    return localized(
      'Phone verification failed.',
      '\u624b\u673a\u53f7\u6821\u9a8c\u5931\u8d25\u3002',
    )
  }
  if (normalized.includes('password was used recently')) {
    return localized(
      'Please use a password you have not used recently.',
      '\u8bf7\u4f7f\u7528\u8fd1\u671f\u672a\u7528\u8fc7\u7684\u65b0\u5bc6\u7801\u3002',
    )
  }
  if (normalized.includes('captcha')) {
    return localized(
      'Please complete the verification code.',
      '\u8bf7\u5148\u5b8c\u6210\u9a8c\u8bc1\u7801\u3002',
    )
  }
  if (errorCode === 'UNAUTHORIZED' || status === 401) {
    return localized('Please sign in and try again.', '\u8bf7\u767b\u5f55\u540e\u518d\u8bd5\u3002')
  }
  return (
    raw ||
    localized(
      'Request failed. Please try again later.',
      '\u8bf7\u6c42\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002',
    )
  )
}

http.interceptors.request.use((config) => {
  const method = (config.method ?? 'get').toLowerCase()
  if (!config.headers.has(traceIdHeader)) {
    config.headers.set(traceIdHeader, createTraceId())
  }
  const deviceFingerprint = browserDeviceFingerprint()
  if (deviceFingerprint && !config.headers.has(deviceFingerprintHeader)) {
    config.headers.set(deviceFingerprintHeader, deviceFingerprint)
  }
  if (unsafeMethods.has(method)) {
    config.headers.set(csrfHeader())
    if (!config.headers.has(idempotencyKeyHeader)) {
      config.headers.set(idempotencyKeyHeader, createTraceId())
    }
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiProblem>) => {
    const config = error.config as RetriableConfig | undefined
    if (
      error.response?.status === 401 &&
      config &&
      !config._retry &&
      config.url !== '/auth/login' &&
      config.url !== '/auth/refresh'
    ) {
      config._retry = true
      try {
        await refreshSession()
        return http(config)
      } catch (refreshError) {
        return Promise.reject(refreshError)
      }
    }
    return Promise.reject(error)
  },
)

function refreshSession(): Promise<void> {
  if (!sessionRefreshPromise) {
    sessionRefreshPromise = coordinateSessionRefresh()
      .catch(async (error: unknown) => {
        await handleSessionExpired()
        throw error
      })
      .finally(() => {
        sessionRefreshPromise = null
      })
  }
  return sessionRefreshPromise
}

async function coordinateSessionRefresh(): Promise<void> {
  const observedGeneration = readSharedStorage(refreshGenerationStorageKey)
  try {
    const lockManager = browserLockManager()
    if (lockManager) {
      let enteredCriticalSection = false
      try {
        await lockManager.request(refreshLockName, async () => {
          enteredCriticalSection = true
          if (refreshGenerationChanged(observedGeneration)) {
            return
          }
          await performSessionRefresh()
        })
        return
      } catch (error) {
        if (enteredCriticalSection) {
          throw error
        }
      }
    }
    await refreshWithStorageLease(observedGeneration)
  } catch (error) {
    if (refreshGenerationChanged(observedGeneration)) {
      return
    }
    throw error
  }
}

function browserLockManager(): LockManagerLike | undefined {
  if (typeof navigator === 'undefined') {
    return undefined
  }
  const candidate = (navigator as Navigator & { locks?: LockManagerLike }).locks
  return candidate && typeof candidate.request === 'function' ? candidate : undefined
}

async function performSessionRefresh(): Promise<void> {
  await http.post('/auth/refresh')
  writeSharedStorage(refreshGenerationStorageKey, `${Date.now()}:${createTraceId()}`)
}

async function refreshWithStorageLease(
  observedGeneration: string | null | undefined,
): Promise<void> {
  if (observedGeneration === undefined) {
    await performSessionRefresh()
    return
  }

  const owner = createTraceId()
  while (true) {
    if (refreshGenerationChanged(observedGeneration)) {
      return
    }

    const now = Date.now()
    const lease = readRefreshLease()
    if (!lease || lease.expiresAt <= now) {
      const candidate: RefreshLease = {
        owner,
        expiresAt: now + refreshLeaseDurationMs,
      }
      if (!writeSharedStorage(refreshLeaseStorageKey, JSON.stringify(candidate))) {
        await performSessionRefresh()
        return
      }

      await pause(0)
      if (readRefreshLease()?.owner === owner) {
        const heartbeat = setInterval(() => renewRefreshLease(owner), refreshLeaseDurationMs / 3)
        try {
          if (!refreshGenerationChanged(observedGeneration)) {
            await performSessionRefresh()
          }
          return
        } finally {
          clearInterval(heartbeat)
          releaseRefreshLease(owner)
        }
      }
    }

    const currentLease = readRefreshLease()
    const waitMs = currentLease
      ? Math.max(1, Math.min(refreshLeasePollMs, currentLease.expiresAt - Date.now()))
      : 1
    await pause(waitMs)
  }
}

function refreshGenerationChanged(observedGeneration: string | null | undefined): boolean {
  if (observedGeneration === undefined) {
    return false
  }
  const currentGeneration = readSharedStorage(refreshGenerationStorageKey)
  return currentGeneration !== undefined && currentGeneration !== observedGeneration
}

function readRefreshLease(): RefreshLease | null {
  const raw = readSharedStorage(refreshLeaseStorageKey)
  if (!raw) {
    return null
  }
  try {
    const lease = JSON.parse(raw) as Partial<RefreshLease>
    if (
      typeof lease.owner === 'string' &&
      lease.owner.length > 0 &&
      typeof lease.expiresAt === 'number' &&
      Number.isFinite(lease.expiresAt)
    ) {
      return { owner: lease.owner, expiresAt: lease.expiresAt }
    }
  } catch {
    // Invalid or stale entries are replaced by the next contender.
  }
  return null
}

function renewRefreshLease(owner: string): void {
  if (readRefreshLease()?.owner !== owner) {
    return
  }
  writeSharedStorage(
    refreshLeaseStorageKey,
    JSON.stringify({ owner, expiresAt: Date.now() + refreshLeaseDurationMs }),
  )
}

function releaseRefreshLease(owner: string): void {
  if (readRefreshLease()?.owner !== owner || typeof localStorage === 'undefined') {
    return
  }
  try {
    localStorage.removeItem(refreshLeaseStorageKey)
  } catch {
    // Storage can be disabled while the page is open; the lease expires on its own.
  }
}

function readSharedStorage(key: string): string | null | undefined {
  if (typeof localStorage === 'undefined') {
    return undefined
  }
  try {
    return localStorage.getItem(key)
  } catch {
    return undefined
  }
}

function writeSharedStorage(key: string, value: string): boolean {
  if (typeof localStorage === 'undefined') {
    return false
  }
  try {
    localStorage.setItem(key, value)
    return true
  } catch {
    return false
  }
}

function pause(delayMs: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, delayMs))
}

async function handleSessionExpired(): Promise<void> {
  if (typeof window === 'undefined') {
    return
  }
  try {
    const { useAuthStore } = await import('@/stores/auth')
    useAuthStore().clearLocalSession()
  } catch {
    // Pinia not yet initialised; fall through to redirect below.
  }
  const pathname = window.location.pathname
  if (pathname === '/login' || pathname === '/') {
    return
  }
  const redirect = pathname + window.location.search
  window.location.assign(`/login?redirect=${encodeURIComponent(redirect)}`)
}

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await http.request<ApiResult<T>>(config)
    const result = response.data
    if (result && result.code === 'OK') {
      return result.data
    }
    throw new ApiError(
      friendlyMessage(result?.message, response.status, result?.code),
      response.status,
      result?.traceId,
      result?.code,
    )
  } catch (error) {
    if (axios.isAxiosError<ApiProblem>(error)) {
      const detail = error.response?.data
      throw new ApiError(
        friendlyMessage(
          detail?.detail || detail?.title || error.message,
          error.response?.status,
          detail?.code,
        ),
        error.response?.status,
        detail?.traceId,
        detail?.code,
        detail,
        normalizeResponseHeaders(error.response?.headers),
      )
    }
    throw error
  }
}

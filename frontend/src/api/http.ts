import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResult, ProblemDetail } from '@/types'
import { csrfHeader } from '@/utils/csrf'

const unsafeMethods = new Set(['post', 'put', 'patch', 'delete'])
const traceIdHeader = 'X-Trace-Id'
const idempotencyKeyHeader = 'Idempotency-Key'
const deviceFingerprintHeader = 'X-Device-Fingerprint'
let fallbackTraceCounter = 0
let pageDeviceFingerprint: string | undefined

interface RetriableConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

export class ApiError extends Error {
  readonly status?: number
  readonly traceId?: string
  readonly code?: string

  constructor(message: string, status?: number, traceId?: string, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.traceId = traceId
    this.code = code
  }
}

export const http = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
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
  return (code || '').trim().toUpperCase().replace(/[\s-]+/g, '_')
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
  if (
    errorCode === 'FORBIDDEN' ||
    status === 403 ||
    normalized === 'operation is not permitted'
  ) {
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
  async (error: AxiosError<ProblemDetail>) => {
    const config = error.config as RetriableConfig | undefined
    if (
      error.response?.status === 401 &&
      config &&
      !config._retry &&
      config.url !== '/auth/login' &&
      config.url !== '/auth/refresh'
    ) {
      config._retry = true
      await http.post('/auth/refresh')
      return http(config)
    }
    return Promise.reject(error)
  },
)

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
    if (axios.isAxiosError<ProblemDetail>(error)) {
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
      )
    }
    throw error
  }
}

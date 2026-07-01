import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import type { ApiResult, ProblemDetail } from '@/types'
import { csrfHeader } from '@/utils/csrf'

const unsafeMethods = new Set(['post', 'put', 'patch', 'delete'])
const traceIdHeader = 'X-Trace-Id'
let fallbackTraceCounter = 0

interface RetriableConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

export class ApiError extends Error {
  readonly status?: number
  readonly traceId?: string

  constructor(message: string, status?: number, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.traceId = traceId
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

http.interceptors.request.use((config) => {
  const method = (config.method ?? 'get').toLowerCase()
  if (!config.headers.has(traceIdHeader)) {
    config.headers.set(traceIdHeader, createTraceId())
  }
  if (unsafeMethods.has(method)) {
    config.headers.set(csrfHeader())
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
    throw new ApiError(result?.message || 'Request failed', response.status, result?.traceId)
  } catch (error) {
    if (axios.isAxiosError<ProblemDetail>(error)) {
      const detail = error.response?.data
      throw new ApiError(
        detail?.detail || detail?.title || error.message,
        error.response?.status,
        detail?.traceId,
      )
    }
    throw error
  }
}

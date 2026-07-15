import { ElMessageBox } from 'element-plus'
import { reactive, readonly } from 'vue'
import { ApiError } from '@/api/http'
import { i18n } from '@/locales'

export type FeedbackLevel = 'success' | 'info' | 'warning' | 'error'

export interface FeedbackOptions {
  key?: string
  title?: string
  message: string
  level?: FeedbackLevel
  duration?: number
  traceId?: string
}

export interface FeedbackItem {
  id: string
  key: string
  title?: string
  message: string
  level: FeedbackLevel
  duration: number
  traceId?: string
  count: number
}

export interface ConfirmOptions {
  title?: string
  content: string
  confirmText?: string
  cancelText?: string
  type?: FeedbackLevel
}

type FeedbackOverrides = Omit<FeedbackOptions, 'message' | 'level'>
type FeedbackInput = FeedbackOverrides | number | undefined

const DEFAULT_DURATION = 4200
const MAX_VISIBLE_ITEMS = 3
const mutableFeedbackItems = reactive<FeedbackItem[]>([])
const dismissalTimers = new Map<string, ReturnType<typeof setTimeout>>()
let nextFeedbackId = 0

export const feedbackItems = readonly(mutableFeedbackItems) as readonly FeedbackItem[]

function translate(key: string): string {
  return String(i18n.global.t(key))
}

function normalizeInput(input: FeedbackInput): FeedbackOverrides {
  return typeof input === 'number' ? { duration: input } : (input ?? {})
}

function clearDismissalTimer(id: string) {
  const timer = dismissalTimers.get(id)
  if (timer !== undefined) {
    clearTimeout(timer)
    dismissalTimers.delete(id)
  }
}

function scheduleDismissal(item: FeedbackItem) {
  clearDismissalTimer(item.id)
  if (item.duration <= 0) {
    return
  }
  dismissalTimers.set(
    item.id,
    setTimeout(() => {
      dismissalTimers.delete(item.id)
      const index = mutableFeedbackItems.findIndex((candidate) => candidate.id === item.id)
      if (index >= 0) {
        mutableFeedbackItems.splice(index, 1)
      }
    }, item.duration),
  )
}

function dismiss(id: string) {
  clearDismissalTimer(id)
  const index = mutableFeedbackItems.findIndex((item) => item.id === id)
  if (index >= 0) {
    mutableFeedbackItems.splice(index, 1)
  }
}

function enqueue(level: FeedbackLevel, message: string, input?: FeedbackInput): string {
  const options = normalizeInput(input)
  const key = options.key ?? `${level}:${message}`
  const existing = mutableFeedbackItems.find((item) => item.key === key)

  if (existing) {
    existing.count += 1
    existing.message = message
    existing.title = options.title ?? existing.title
    existing.duration = options.duration ?? existing.duration
    existing.traceId = options.traceId ?? existing.traceId
    scheduleDismissal(existing)
    return existing.id
  }

  while (mutableFeedbackItems.length >= MAX_VISIBLE_ITEMS) {
    const oldest = mutableFeedbackItems[0]
    if (!oldest) {
      break
    }
    dismiss(oldest.id)
  }

  nextFeedbackId += 1
  const item: FeedbackItem = {
    id: `feedback-${nextFeedbackId}`,
    key,
    title: options.title,
    message,
    level,
    duration: options.duration ?? DEFAULT_DURATION,
    traceId: options.traceId,
    count: 1,
  }
  mutableFeedbackItems.push(item)
  scheduleDismissal(item)
  return item.id
}

function apiMetadata(error: unknown): { status?: number; traceId?: string } {
  if (error instanceof ApiError) {
    return { status: error.status, traceId: error.traceId }
  }
  if (!error || typeof error !== 'object') {
    return {}
  }
  const candidate = error as {
    status?: number
    traceId?: string
    response?: { status?: number; data?: { traceId?: string } }
  }
  return {
    status: candidate.status ?? candidate.response?.status,
    traceId: candidate.traceId ?? candidate.response?.data?.traceId,
  }
}

function safeFallback(fallbackKey: string): string {
  return i18n.global.te(fallbackKey) ? translate(fallbackKey) : translate('feedback.requestFailed')
}

export function normalizeFeedbackMessage(message: string): string {
  const normalized = message.trim().toLowerCase()
  if (
    normalized.includes('too many requests') ||
    normalized.includes('too many attempts') ||
    normalized.includes('temporarily locked')
  ) {
    return translate('feedback.rateLimited')
  }
  if (
    normalized === 'operation is not permitted' ||
    normalized.includes('permission denied') ||
    normalized === 'forbidden'
  ) {
    return translate('feedback.forbidden')
  }
  if (normalized === 'unauthorized' || normalized.includes('session expired')) {
    return translate('feedback.unauthorized')
  }
  return message
}

export function clearFeedback() {
  for (const id of dismissalTimers.keys()) {
    clearDismissalTimer(id)
  }
  mutableFeedbackItems.splice(0)
  nextFeedbackId = 0
}

export function useNotify() {
  function notify(level: FeedbackLevel, message: string, input?: FeedbackInput): string {
    return enqueue(level, normalizeFeedbackMessage(message), input)
  }

  function success(message: string, input?: FeedbackInput): string {
    return enqueue('success', message, input)
  }

  function info(message: string, input?: FeedbackInput): string {
    return enqueue('info', normalizeFeedbackMessage(message), input)
  }

  function warning(message: string, input?: FeedbackInput): string {
    return enqueue('warning', normalizeFeedbackMessage(message), input)
  }

  function error(message: string, input?: FeedbackInput): string {
    return enqueue('error', normalizeFeedbackMessage(message), input)
  }

  function fromApiError(apiError: unknown, fallbackKey: string): string {
    const { status, traceId } = apiMetadata(apiError)
    if (status === 401) {
      return warning(translate('feedback.unauthorized'), {
        key: 'api:unauthorized',
        traceId,
      })
    }
    if (status === 403) {
      return warning(translate('feedback.forbidden'), {
        key: 'api:forbidden',
        traceId,
      })
    }
    if (status === 429) {
      return warning(translate('feedback.rateLimited'), {
        key: 'api:rate-limited',
        traceId,
      })
    }
    return error(safeFallback(fallbackKey), {
      key: `api:${status ?? 'unknown'}:${fallbackKey}`,
      traceId,
    })
  }

  async function confirm(options: ConfirmOptions): Promise<boolean> {
    try {
      await ElMessageBox.confirm(options.content, options.title ?? translate('common.confirm'), {
        confirmButtonText: options.confirmText ?? translate('common.ok'),
        cancelButtonText: options.cancelText ?? translate('common.cancel'),
        type: options.type ?? 'warning',
        confirmButtonClass: 'el-button--primary',
      })
      return true
    } catch {
      return false
    }
  }

  return {
    notify,
    success,
    info,
    warning,
    error,
    fromApiError,
    confirm,
    dismiss,
    normalize: normalizeFeedbackMessage,
  }
}

import { trackEvent } from '@/TrackingSdk'

const references = new WeakMap<object, string>()
const reported = new WeakSet<object>()
let fallbackCounter = 0

function createReference(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `ui-${crypto.randomUUID()}`
  }
  fallbackCounter += 1
  return `ui-${Date.now().toString(36)}-${fallbackCounter.toString(36)}`
}

function errorObject(error: unknown): object | null {
  return (typeof error === 'object' && error !== null) || typeof error === 'function'
    ? (error as object)
    : null
}

export function getUiErrorReference(error: unknown): string {
  const candidate = errorObject(error)
  if (!candidate) {
    return createReference()
  }
  const existing = references.get(candidate)
  if (existing) {
    return existing
  }
  const reference = createReference()
  references.set(candidate, reference)
  return reference
}

export function reportUiError(
  error: unknown,
  info = '',
  reference = getUiErrorReference(error),
): string {
  const candidate = errorObject(error)
  if (candidate && reported.has(candidate)) {
    return reference
  }
  if (candidate) {
    reported.add(candidate)
  }

  const normalized = error instanceof Error ? error : new Error(String(error))
  trackEvent('UI_ERROR', {
    traceId: reference,
    source: 'web-ui',
    attributes: {
      errorName: normalized.name.slice(0, 64),
      message: normalized.message.slice(0, 256),
      info: info.slice(0, 256),
    },
  })
  return reference
}

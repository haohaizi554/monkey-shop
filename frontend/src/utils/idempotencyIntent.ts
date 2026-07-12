const storagePrefix = 'monkeyshop:idempotency-intent:v1:'
const memoryFallback = new Map<string, string>()
let fallbackKeyCounter = 0

export interface IdempotencyIntent {
  readonly key: string
  complete(): void
}

export function getIdempotencyIntent(intent: string, payload: unknown): IdempotencyIntent {
  const normalizedIntent = intent.trim()
  if (!normalizedIntent) {
    throw new TypeError('Idempotency intent must not be empty')
  }

  const storageKey = `${storagePrefix}${stableHash(normalizedIntent)}:${stableHash(stableSerialize(payload))}`
  const existingKey = readIntentKey(storageKey)
  const key = existingKey || createIdempotencyKey()
  if (!existingKey) {
    writeIntentKey(storageKey, key)
  }

  return {
    key,
    complete() {
      if (readIntentKey(storageKey) === key) {
        removeIntentKey(storageKey)
      }
    },
  }
}

function stableSerialize(value: unknown): string {
  const normalized = normalizeValue(value, new Set<object>())
  return JSON.stringify(normalized) ?? 'undefined'
}

function normalizeValue(value: unknown, ancestors: Set<object>): unknown {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') {
    return value
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null
  }
  if (typeof value === 'undefined' || typeof value === 'function' || typeof value === 'symbol') {
    return undefined
  }
  if (typeof value === 'bigint') {
    throw new TypeError('BigInt is not supported in an idempotency payload')
  }
  if (value instanceof Date) {
    return value.toJSON()
  }
  if (ancestors.has(value)) {
    throw new TypeError('Circular idempotency payloads are not supported')
  }

  ancestors.add(value)
  try {
    if (Array.isArray(value)) {
      return value.map((item) => normalizeValue(item, ancestors) ?? null)
    }
    const normalized: Record<string, unknown> = {}
    for (const key of Object.keys(value).sort()) {
      const item = normalizeValue((value as Record<string, unknown>)[key], ancestors)
      if (item !== undefined) {
        normalized[key] = item
      }
    }
    return normalized
  } finally {
    ancestors.delete(value)
  }
}

function stableHash(value: string): string {
  let first = 0x811c9dc5
  let second = 0x9e3779b9
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index)
    first = Math.imul(first ^ code, 0x01000193)
    second = Math.imul(second ^ code, 0x85ebca6b)
  }
  return `${(first >>> 0).toString(36)}${(second >>> 0).toString(36)}${value.length.toString(36)}`
}

function createIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const bytes = crypto.getRandomValues(new Uint8Array(16))
    return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  }
  fallbackKeyCounter += 1
  return `intent-${Date.now().toString(36)}-${fallbackKeyCounter.toString(36)}`
}

function readIntentKey(storageKey: string): string | null {
  try {
    const stored = typeof sessionStorage === 'undefined' ? null : sessionStorage.getItem(storageKey)
    if (stored) {
      return stored
    }
  } catch {
    // Sandboxed browsers can deny sessionStorage access.
  }
  return memoryFallback.get(storageKey) ?? null
}

function writeIntentKey(storageKey: string, key: string): void {
  try {
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.setItem(storageKey, key)
      memoryFallback.delete(storageKey)
      return
    }
  } catch {
    // Fall back to page memory while preserving retry stability.
  }
  memoryFallback.set(storageKey, key)
}

function removeIntentKey(storageKey: string): void {
  try {
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.removeItem(storageKey)
    }
  } catch {
    // The in-memory entry is still cleared below.
  }
  memoryFallback.delete(storageKey)
}

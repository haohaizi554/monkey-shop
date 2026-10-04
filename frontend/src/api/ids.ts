export type ApiId = string | number

export function normalizeApiId(value: unknown): string {
  const candidate = Array.isArray(value) ? value[0] : value
  return candidate == null ? '' : String(candidate).trim()
}

export function parsePositiveApiId(value: unknown): ApiId | undefined {
  const candidate = Array.isArray(value) ? value[0] : value
  if (typeof candidate === 'number') {
    return Number.isSafeInteger(candidate) && candidate > 0 ? candidate : undefined
  }
  const normalized = normalizeApiId(candidate)
  return /^[1-9]\d*$/.test(normalized) ? normalized : undefined
}

export function isPositiveApiId(value: unknown): boolean {
  return parsePositiveApiId(value) !== undefined
}

export function sameApiId(left: unknown, right: unknown): boolean {
  const normalizedLeft = normalizeApiId(left)
  return normalizedLeft.length > 0 && normalizedLeft === normalizeApiId(right)
}

export type ApiId = string | number

export function normalizeApiId(value: unknown): string {
  const candidate = Array.isArray(value) ? value[0] : value
  return candidate == null ? '' : String(candidate).trim()
}

export function isPositiveApiId(value: unknown): boolean {
  return /^[1-9]\d*$/.test(normalizeApiId(value))
}

export function sameApiId(left: unknown, right: unknown): boolean {
  const normalizedLeft = normalizeApiId(left)
  return normalizedLeft.length > 0 && normalizedLeft === normalizeApiId(right)
}

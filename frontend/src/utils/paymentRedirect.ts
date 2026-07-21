export interface PaymentRedirectNavigator {
  assign(url: string): void
}

const EXPLICIT_SCHEME = /^[a-z][a-z\d+.-]*:/i
const SAFE_RELATIVE_PREFIX = /^(?:\/|\.\/|\.\.\/|\?|#)/

function containsControlCharacter(value: string): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0) ?? 0
    return codePoint <= 31 || codePoint === 127
  })
}

export function resolvePaymentRedirectUrl(
  candidate: string | undefined,
  currentPageUrl: string,
): string | null {
  const value = candidate?.trim()
  if (!value || containsControlCharacter(value)) {
    return null
  }
  if (!EXPLICIT_SCHEME.test(value) && !SAFE_RELATIVE_PREFIX.test(value)) {
    return null
  }
  if (value.startsWith('//')) {
    return null
  }

  try {
    const currentPage = new URL(currentPageUrl)
    const destination = new URL(value, currentPage)
    const isSameOrigin = destination.origin === currentPage.origin
    const usesAllowedProtocol =
      destination.protocol === 'https:' || (destination.protocol === 'http:' && isSameOrigin)

    if (!usesAllowedProtocol || destination.username || destination.password) {
      return null
    }

    return destination.href
  } catch {
    return null
  }
}

export function navigateToPaymentProvider(
  destination: string,
  navigator: PaymentRedirectNavigator = window.location,
): void {
  navigator.assign(destination)
}

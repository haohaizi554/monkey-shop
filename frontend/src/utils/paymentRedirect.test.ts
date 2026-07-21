import { describe, expect, it, vi } from 'vitest'
import { navigateToPaymentProvider, resolvePaymentRedirectUrl } from './paymentRedirect'

const CURRENT_PAGE = 'http://localhost:5173/payment/42'

describe('resolvePaymentRedirectUrl', () => {
  it.each([
    ['/provider/checkout?payment=PAY-42', 'http://localhost:5173/provider/checkout?payment=PAY-42'],
    ['http://localhost:5173/provider/checkout', 'http://localhost:5173/provider/checkout'],
    ['https://pay.example.test/checkout/PAY-42', 'https://pay.example.test/checkout/PAY-42'],
  ])('accepts a safe payment destination: %s', (candidate, expected) => {
    expect(resolvePaymentRedirectUrl(candidate, CURRENT_PAGE)).toBe(expected)
  })

  it.each([
    undefined,
    '',
    'javascript:alert(document.domain)',
    'data:text/html,<script>alert(1)</script>',
    'file:///etc/passwd',
    'http://pay.example.test/checkout/PAY-42',
    '//pay.example.test/checkout/PAY-42',
    'https://user:password@pay.example.test/checkout/PAY-42',
    'not a valid payment URL',
  ])('rejects an unsafe payment destination: %s', (candidate) => {
    expect(resolvePaymentRedirectUrl(candidate, CURRENT_PAGE)).toBeNull()
  })
})

describe('navigateToPaymentProvider', () => {
  it('uses top-level navigation for the validated destination', () => {
    const assign = vi.fn()

    navigateToPaymentProvider('https://pay.example.test/checkout/PAY-42', { assign })

    expect(assign).toHaveBeenCalledOnce()
    expect(assign).toHaveBeenCalledWith('https://pay.example.test/checkout/PAY-42')
  })
})

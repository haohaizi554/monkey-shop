import { beforeEach, describe, expect, it } from 'vitest'
import {
  orderStatusLabel,
  paymentMethodLabel,
  paymentStatusLabel,
  trackingStatusLabel,
} from '@/utils/format'

describe('localized status formatting', () => {
  beforeEach(() => {
    localStorage.setItem('monkeyshop-locale', 'zh')
  })

  it('renders readable Chinese labels for known backend values', () => {
    expect(orderStatusLabel('PAID')).toBe('\u5df2\u652f\u4ed8')
    expect(paymentMethodLabel('WECHAT')).toBe('\u5fae\u4fe1\u652f\u4ed8')
    expect(paymentStatusLabel('REFUNDED')).toBe('\u5df2\u9000\u6b3e')
    expect(trackingStatusLabel('IN_TRANSIT')).toBe('\u8fd0\u8f93\u4e2d')
  })

  it('never exposes an unknown internal enum token', () => {
    expect(orderStatusLabel('PROVIDER_INTERNAL_STATE')).toBe('\u672a\u77e5')

    localStorage.setItem('monkeyshop-locale', 'en')
    expect(paymentStatusLabel('PROVIDER_INTERNAL_STATE')).toBe('Unknown')
  })
})

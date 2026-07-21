import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, type App, type Component } from 'vue'
import { ElMessageBox } from 'element-plus'
import ConfirmAction from './ConfirmAction.vue'
import FormSection from './FormSection.vue'
import InlineNotice from './InlineNotice.vue'
import StatusTag, { resolveStatusTag } from './StatusTag.vue'
import { i18n } from '@/locales'

const mounted: Array<{ app: App; host: HTMLElement }> = []

function mount(component: Component, props: Record<string, unknown> = {}, slots = {}) {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp({ render: () => h(component, props, slots) })
  app.use(i18n)
  app.mount(host)
  mounted.push({ app, host })
  return host
}

beforeEach(() => {
  i18n.global.locale.value = 'zh'
})

afterEach(() => {
  vi.restoreAllMocks()
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
})

describe('commerce UI primitives', () => {
  it('groups related fields without creating a decorative card', () => {
    const host = mount(
      FormSection,
      { title: '收货信息', description: '用于配送与联系' },
      { default: () => h('input', { name: 'receiver' }) },
    )

    expect(host.querySelector('fieldset')).not.toBeNull()
    expect(host.querySelector('legend')?.textContent).toContain('收货信息')
    expect(host.querySelector('.form-section')?.classList.contains('card')).toBe(false)
    expect(host.querySelector('input[name="receiver"]')).not.toBeNull()
  })

  it('renders rate limits inline with an alert role and retry countdown', () => {
    const host = mount(InlineNotice, {
      severity: 'warning',
      message: '操作过于频繁，请稍后再试。',
      retryAfterSeconds: 12,
    })

    const notice = host.querySelector('.inline-notice')
    expect(notice?.getAttribute('role')).toBe('alert')
    expect(notice?.getAttribute('data-severity')).toBe('warning')
    expect(host.textContent).toContain('12')
    expect(host.textContent).not.toContain('Too many requests')
  })

  it('maps cross-domain statuses to stable tones and labels', () => {
    expect(resolveStatusTag('PENDING_PAYMENT')).toEqual({
      tone: 'warning',
      labelKey: 'status.order.pendingPayment',
    })
    expect(resolveStatusTag('PAID')).toMatchObject({ tone: 'info' })
    expect(resolveStatusTag('OUT_OF_STOCK')).toMatchObject({ tone: 'danger' })
    expect(resolveStatusTag('BLOCKED')).toMatchObject({ tone: 'danger' })

    const host = mount(StatusTag, { status: 'PENDING_PAYMENT' })
    expect(host.querySelector('.status-tag')?.getAttribute('data-tone')).toBe('warning')
    expect(host.textContent).toContain('待支付')
  })

  it('runs one scoped async confirmation and returns focus to the trigger', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue({} as never)
    let resolveAction!: () => void
    const action = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          resolveAction = resolve
        }),
    )
    const host = mount(ConfirmAction, { content: '确认删除？', action }, { default: () => '删除' })
    const button = host.querySelector<HTMLButtonElement>('button')
    button?.focus()
    button?.click()
    await vi.waitFor(() => expect(action).toHaveBeenCalledOnce())
    expect(button?.disabled).toBe(true)
    expect(button?.getAttribute('aria-busy')).toBe('true')

    resolveAction()
    await vi.waitFor(() => expect(button?.disabled).toBe(false))
    expect(document.activeElement).toBe(button)
  })
})

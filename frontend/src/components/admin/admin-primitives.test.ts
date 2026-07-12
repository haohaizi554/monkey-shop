import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, h, type App, type Component } from 'vue'
import AdminPageToolbar from '@/components/admin/AdminPageToolbar.vue'
import MetricStrip from '@/components/admin/MetricStrip.vue'
import { i18n } from '@/locales'

const mountedApps: Array<{ app: App; host: HTMLElement }> = []

function mount(component: Component, props: Record<string, unknown> = {}, slots = {}) {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp({ render: () => h(component, props, slots) })
  app.use(i18n)
  app.mount(host)
  mountedApps.push({ app, host })
  return host
}

beforeEach(() => {
  i18n.global.locale.value = 'en'
})

afterEach(() => {
  for (const { app, host } of mountedApps.splice(0)) {
    app.unmount()
    host.remove()
  }
})

describe('AdminPageToolbar', () => {
  it('keeps search, filters, and actions in explicit regions', () => {
    const host = mount(
      AdminPageToolbar,
      { ariaLabel: 'Product controls' },
      {
        search: () => h('input', { type: 'search' }),
        filters: () => h('select'),
        actions: () => h('button', 'Create product'),
      },
    )

    expect(host.querySelector('.admin-page-toolbar')?.getAttribute('aria-label')).toBe(
      'Product controls',
    )
    expect(host.querySelector('.admin-page-toolbar__search input')).not.toBeNull()
    expect(host.querySelector('.admin-page-toolbar__filters select')).not.toBeNull()
    expect(host.querySelector('.admin-page-toolbar__actions button')?.textContent).toBe(
      'Create product',
    )
  })
})

describe('MetricStrip', () => {
  it('renders a semantic list with stable metric keys and tones', () => {
    const host = mount(MetricStrip, {
      items: [
        { key: 'orders', label: 'Orders', value: 42, tone: 'info' },
        { key: 'risk', label: 'Risk cases', value: 3, tone: 'warning' },
      ],
    })

    expect(host.querySelector('.metric-strip')?.tagName).toBe('UL')
    expect(host.querySelectorAll('.metric-strip__item')).toHaveLength(2)
    expect(host.querySelector('[data-metric-key="orders"]')?.textContent).toContain('42')
    expect(host.querySelector('[data-tone="warning"]')?.textContent).toContain('Risk cases')
  })
})

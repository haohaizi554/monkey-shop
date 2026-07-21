import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, type App, type Component } from 'vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { i18n } from '@/locales'

const mountedApps: Array<{ app: App; host: HTMLElement }> = []

beforeEach(() => {
  i18n.global.locale.value = 'en'
})

function mount(component: Component, props: Record<string, unknown> = {}, slots = {}) {
  const host = document.createElement('div')
  document.body.append(host)
  const app = createApp({ render: () => h(component, props, slots) })
  app.use(i18n)
  app.mount(host)
  mountedApps.push({ app, host })
  return host
}

afterEach(() => {
  for (const { app, host } of mountedApps.splice(0)) {
    app.unmount()
    host.remove()
  }
})

describe('PageHeader', () => {
  it('renders one unframed title surface with optional content', () => {
    const host = mount(
      PageHeader,
      { title: 'Inventory', eyebrow: 'Operations', description: 'Warehouse stock' },
      { actions: () => h('button', 'Refresh') },
    )

    expect(host.querySelectorAll('h1')).toHaveLength(1)
    expect(host.querySelector('h1')?.textContent).toBe('Inventory')
    expect(host.querySelector('.page-header__eyebrow')?.textContent).toBe('Operations')
    expect(host.querySelector('.page-header__actions button')?.textContent).toBe('Refresh')
    expect(host.querySelector('.page-header')?.classList.contains('card')).toBe(false)
  })

  it('keeps an optional brand visual outside the title and action regions', () => {
    const host = mount(
      PageHeader,
      { title: 'Discover' },
      {
        actions: () => h('button', 'Refresh'),
        visual: () => h('img', { src: '/mascot.webp', alt: '' }),
      },
    )

    expect(host.querySelectorAll('h1')).toHaveLength(1)
    expect(host.querySelector('.page-header__actions button')?.textContent).toBe('Refresh')
    expect(host.querySelector('.page-header__visual img')?.getAttribute('alt')).toBe('')
  })
})

describe('AsyncStateView', () => {
  it('publishes the requested layout mode without changing state ownership', () => {
    const host = mount(
      AsyncStateView,
      { status: 'success', mode: 'grid' },
      { default: () => h('p', 'Products') },
    )

    expect(host.querySelector('.async-state-view')?.getAttribute('data-mode')).toBe('grid')
    expect(host.textContent).toContain('Products')
  })

  it('renders only the branch selected by status', () => {
    const host = mount(AsyncStateView, {
      status: 'empty',
      error: 'This error must stay hidden',
      emptyTitle: 'Nothing here',
    })

    expect(host.querySelector('.async-state-view__empty')).not.toBeNull()
    expect(host.querySelector('.async-state-view__error')).toBeNull()
    expect(host.textContent).not.toContain('This error must stay hidden')
  })

  it('offers recovery from the error branch', () => {
    const retry = vi.fn()
    const host = mount(AsyncStateView, {
      status: 'error',
      error: 'Unable to load inventory',
      onRetry: retry,
    })

    const button = host.querySelector<HTMLButtonElement>('.async-state-view__retry')
    expect(button).not.toBeNull()
    button?.click()
    expect(retry).toHaveBeenCalledOnce()
  })

  it('falls back to controlled copy for an unknown error string', () => {
    const host = mount(AsyncStateView, {
      status: 'error',
      error: 'Raw provider secret',
    })

    expect(host.textContent).toContain('The request failed. Please try again later.')
    expect(host.textContent).not.toContain('Raw provider secret')
  })

  it('keeps content visible behind a compact updating indicator', () => {
    const host = mount(
      AsyncStateView,
      { status: 'updating' },
      { default: () => h('p', { class: 'valid-content' }, 'Previous valid result') },
    )

    expect(host.querySelector('.valid-content')?.textContent).toBe('Previous valid result')
    expect(host.querySelector('.async-state-view__updating')).not.toBeNull()
    expect(host.querySelector('.async-state-view__loading')).toBeNull()
  })

  it('keeps stale content visible with an explicit refresh failure', () => {
    const retry = vi.fn()
    const host = mount(
      AsyncStateView,
      {
        status: 'error',
        error: 'common.requestFailed',
        preserveContentOnError: true,
        onRetry: retry,
      },
      { default: () => h('p', { class: 'stale-content' }, 'Previous valid result') },
    )

    expect(host.querySelector('.stale-content')?.textContent).toBe('Previous valid result')
    expect(host.querySelector('.async-state-view__stale-error')).not.toBeNull()
    host.querySelector<HTMLButtonElement>('.async-state-view__retry')?.click()
    expect(retry).toHaveBeenCalledOnce()
  })
})

describe('DataTableShell', () => {
  it('owns the horizontal scroller when data is present', () => {
    const host = mount(
      DataTableShell,
      { ariaLabel: 'Products' },
      { default: () => h('table', { style: 'min-width: 1200px' }) },
    )

    expect(host.querySelector('.data-table-shell__scroller table')).not.toBeNull()
    const scroller = host.querySelector('.data-table-shell__scroller')
    expect(scroller?.getAttribute('role')).toBe('region')
    expect(scroller?.getAttribute('tabindex')).toBe('0')
    expect(scroller?.getAttribute('aria-label')).toBe('Products')
  })

  it('shows the empty slot instead of a stale table', () => {
    const host = mount(
      DataTableShell,
      { empty: true },
      {
        default: () => h('table'),
        empty: () => h('p', 'No products'),
      },
    )

    expect(host.querySelector('.data-table-shell__empty')?.textContent).toContain('No products')
    expect(host.querySelector('.data-table-shell__scroller')).toBeNull()
  })
})

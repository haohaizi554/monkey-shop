import { afterEach, describe, expect, it } from 'vitest'
import { createApp, h, type App } from 'vue'
import MascotState from '@/components/mascot/MascotState.vue'

const mountedApps: Array<{ app: App; host: HTMLElement }> = []

function mountMascot(props: Record<string, unknown>) {
  const host = document.createElement('div')
  document.body.append(host)

  const app = createApp({ render: () => h(MascotState, props) })
  app.config.warnHandler = () => undefined
  app.mount(host)
  mountedApps.push({ app, host })

  return host.querySelector<HTMLImageElement>('img')
}

afterEach(() => {
  for (const { app, host } of mountedApps.splice(0)) {
    app.unmount()
    host.remove()
  }
})

describe('MascotState', () => {
  it.each([
    ['welcome', 'monkey-welcome-1x.webp'],
    ['shoppingBag', 'monkey-shopping-bag-1x.webp'],
    ['search', 'monkey-search-1x.webp'],
    ['cart', 'monkey-cart-1x.webp'],
    ['package', 'monkey-package-1x.webp'],
    ['celebrate', 'monkey-celebrate-1x.webp'],
    ['clipboard', 'monkey-clipboard-1x.webp'],
    ['warning', 'monkey-warning-1x.webp'],
    ['shield', 'monkey-shield-1x.webp'],
    ['support', 'monkey-support-1x.webp'],
    ['dashboard', 'monkey-dashboard-1x.webp'],
    ['hourglass', 'monkey-hourglass-1x.webp'],
  ])('maps the %s pose to its standalone asset', (pose, filename) => {
    const image = mountMascot({ pose, alt: '状态提示' })

    expect(image?.getAttribute('src')).toContain(filename)
  })

  it.each([
    ['sm', '128'],
    ['md', '192'],
    ['lg', '288'],
  ])('renders the %s size with stable intrinsic dimensions', (size, dimension) => {
    const image = mountMascot({ pose: 'search', size, alt: '没有匹配商品' })

    expect(image?.getAttribute('width')).toBe(dimension)
    expect(image?.getAttribute('height')).toBe(dimension)
  })

  it('passes meaningful alternative text to a non-decorative image', () => {
    const image = mountMascot({
      pose: 'search',
      size: 'md',
      alt: '没有匹配商品',
      decorative: false,
    })

    expect(image?.getAttribute('alt')).toBe('没有匹配商品')
    expect(image?.hasAttribute('aria-hidden')).toBe(false)
    expect(image?.getAttribute('loading')).toBe('lazy')
    expect(image?.getAttribute('decoding')).toBe('async')
  })

  it('hides a decorative image from assistive technology', () => {
    const image = mountMascot({
      pose: 'celebrate',
      alt: '操作成功',
      decorative: true,
    })

    expect(image?.getAttribute('alt')).toBe('')
    expect(image?.getAttribute('aria-hidden')).toBe('true')
  })

  it('uses the welcome 2x resource without loading a pose sheet', () => {
    const image = mountMascot({ pose: 'welcome', alt: '欢迎来到 MonkeyShop' })

    expect(image?.getAttribute('srcset')).toContain('monkey-welcome-2x.webp 2x')
    expect(image?.getAttribute('srcset')).not.toContain('pose-sheet')
  })

  it('falls back to welcome and md for illegal runtime prop values', () => {
    const image = mountMascot({ pose: 'unknown', size: 'huge', alt: '欢迎来到 MonkeyShop' })

    expect(image?.getAttribute('src')).toContain('monkey-welcome-1x.webp')
    expect(image?.getAttribute('width')).toBe('192')
    expect(image?.getAttribute('height')).toBe('192')
  })
})

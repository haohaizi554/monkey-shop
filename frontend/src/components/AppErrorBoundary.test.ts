import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import { createApp, h, nextTick, ref, type App, type Component } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import AppErrorBoundary from './AppErrorBoundary.vue'
import { i18n } from '@/locales'

interface MountedBoundary {
  app: App
  host: HTMLElement
  router: Router
}

const mounted: MountedBoundary[] = []

async function mountBoundary(child: Component): Promise<MountedBoundary> {
  const host = document.createElement('div')
  document.body.append(host)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/first', component: { template: '<div>first</div>' } },
      { path: '/second', component: { template: '<div>second</div>' } },
    ],
  })
  await router.push('/first')
  await router.isReady()
  i18n.global.locale.value = 'en'

  const app = createApp({
    render: () => h(AppErrorBoundary, null, { default: () => h(child) }),
  })
  app.config.errorHandler = () => undefined
  app.use(router).use(i18n).use(ElementPlus)
  app.mount(host)
  const view = { app, host, router }
  mounted.push(view)
  await nextTick()
  return view
}

afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
})

describe('AppErrorBoundary', () => {
  it('renders a sanitized alert and retry remounts the failed child', async () => {
    const shouldThrow = ref(true)
    const secret = 'database-password=do-not-render'
    const ThrowingChild = {
      setup() {
        return () => {
          if (shouldThrow.value) throw new Error(`${secret}\nstack: secret-stack`) // intentional test fault
          return h('p', { 'data-testid': 'child-content' }, 'Recovered child')
        }
      },
    }
    const { host } = await mountBoundary(ThrowingChild)

    const alert = host.querySelector<HTMLElement>('[role="alert"]')
    expect(alert).not.toBeNull()
    expect(alert?.textContent).toContain('Something went wrong')
    expect(alert?.textContent).toMatch(/Support reference:\s+ui-/)
    expect(alert?.textContent).not.toContain(secret)
    expect(alert?.textContent).not.toContain('secret-stack')
    expect(host.querySelectorAll('main')).toHaveLength(0)

    const retry = alert?.querySelector<HTMLButtonElement>('button')
    expect(retry).not.toBeNull()
    expect(retry?.tabIndex).toBeGreaterThanOrEqual(0)
    expect(retry?.getAttribute('data-touch-target')).toBe('44')

    shouldThrow.value = false
    retry?.click()
    await nextTick()
    expect(host.querySelector('[data-testid="child-content"]')?.textContent).toBe('Recovered child')
    expect(host.querySelector('[role="alert"]')).toBeNull()
  })

  it('clears a failed render when the route changes', async () => {
    const shouldThrow = ref(true)
    const RouteChild = {
      setup() {
        return () => {
          if (shouldThrow.value) throw new Error('route-secret')
          return h('p', { 'data-testid': 'route-child' }, 'Route content')
        }
      },
    }
    const { host, router } = await mountBoundary(RouteChild)
    expect(host.querySelector('[role="alert"]')).not.toBeNull()

    shouldThrow.value = false
    await router.push('/second')
    await nextTick()
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(host.querySelector('[data-testid="route-child"]')?.textContent).toBe('Route content')
  })
})

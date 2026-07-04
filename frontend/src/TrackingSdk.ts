import type { Router } from 'vue-router'
import { recordTrackingEvent } from '@/api/tracking'
import type { TrackingEventRequest, TrackingEventType } from '@/types'

const sessionKey = 'monkeyshop-tracking-session'
let clickListenerInstalled = false

function createId(prefix: string): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `${prefix}-${crypto.randomUUID()}`
  }
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

function sessionId(): string {
  if (typeof sessionStorage === 'undefined') {
    return createId('session')
  }
  const existing = sessionStorage.getItem(sessionKey)
  if (existing) {
    return existing
  }
  const created = createId('session')
  sessionStorage.setItem(sessionKey, created)
  return created
}

function send(eventType: TrackingEventType, payload: Partial<TrackingEventRequest>) {
  void recordTrackingEvent({
    eventType,
    sessionId: sessionId(),
    traceId: payload.traceId ?? createId('trace'),
    page: payload.page ?? window.location.pathname,
    source: payload.source ?? 'web',
    occurredAt: new Date().toISOString(),
    attributes: payload.attributes,
    productId: payload.productId,
    categoryId: payload.categoryId,
    orderId: payload.orderId,
    amount: payload.amount,
  }).catch(() => undefined)
}

function inferProductId(path: string): number | undefined {
  const match = path.match(/^\/shop\/(\d+)/)
  return match ? Number(match[1]) : undefined
}

export function trackEvent(eventType: TrackingEventType, payload: Partial<TrackingEventRequest> = {}) {
  send(eventType, payload)
}

export function installTracking(router: Router) {
  router.afterEach((to) => {
    const productId = inferProductId(to.path)
    send(productId ? 'PRODUCT_VIEW' : 'PAGE_VIEW', {
      page: to.fullPath,
      productId,
      attributes: { route: to.name ? String(to.name) : to.path },
    })
  })

  if (!clickListenerInstalled && typeof document !== 'undefined') {
    clickListenerInstalled = true
    document.addEventListener('click', (event) => {
      const target = event.target instanceof Element ? event.target.closest('button,a') : null
      if (!target) {
        return
      }
      send('CLICK', {
        page: window.location.pathname,
        attributes: {
          tag: target.tagName.toLowerCase(),
          text: (target.textContent ?? '').trim().slice(0, 64),
        },
      })
    })
  }
}

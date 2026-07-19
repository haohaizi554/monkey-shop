import { describe, expect, it } from 'vitest'
import { router } from './index'

function metaFor(path: string) {
  return router.resolve(path).meta
}

describe('route shell metadata', () => {
  it('assigns every route to one root-owned application area', () => {
    for (const route of router.getRoutes()) {
      expect(['auth', 'consumer', 'admin'], route.path).toContain(route.meta.area)
      expect(route.meta.titleKey, route.path).toBeTypeOf('string')
    }
  })

  it('hides mobile consumer navigation during checkout and payment', () => {
    expect(metaFor('/checkout').hideConsumerBottomNav).toBe(true)
    expect(metaFor('/payment/42').hideConsumerBottomNav).toBe(true)
    expect(metaFor('/shop').hideConsumerBottomNav).not.toBe(true)
  })

  it('keeps auth, consumer, and admin routes distinct', () => {
    expect(metaFor('/login').area).toBe('auth')
    expect(metaFor('/shop').area).toBe('consumer')
    expect(metaFor('/admin').area).toBe('admin')
  })

  it('protects every commerce operations workspace as an admin route', () => {
    for (const path of [
      '/admin/orders',
      '/admin/returns',
      '/admin/payments',
      '/admin/logistics',
      '/admin/members',
    ]) {
      expect(metaFor(path).area, path).toBe('admin')
      expect(metaFor(path).requiresAuth, path).toBe(true)
      expect(metaFor(path).requiresAdmin, path).toBe(true)
    }
  })
})

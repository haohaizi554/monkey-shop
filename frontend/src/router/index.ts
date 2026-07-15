import { nextTick } from 'vue'
import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import { i18n } from '@/locales'
import { useAuthStore } from '@/stores/auth'

function requiresAuth(route: RouteLocationNormalized): boolean {
  return route.matched.some((record) => record.meta.requiresAuth)
}

function requiresAdmin(route: RouteLocationNormalized): boolean {
  return route.matched.some((record) => record.meta.requiresAdmin)
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/shop', meta: { area: 'consumer', titleKey: 'nav.shop' } },
    {
      path: '/login',
      component: () => import('@/views/LoginView.vue'),
      meta: { area: 'auth', titleKey: 'nav.login', publicOnly: true },
    },
    {
      path: '/shop',
      component: () => import('@/views/ShopView.vue'),
      meta: { area: 'consumer', titleKey: 'nav.shop' },
    },
    {
      path: '/shop/:productId',
      component: () => import('@/views/ProductDetailView.vue'),
      meta: { area: 'consumer', titleKey: 'common.product' },
    },
    {
      path: '/search',
      component: () => import('@/views/SearchView.vue'),
      meta: { area: 'consumer', titleKey: 'nav.search' },
    },
    {
      path: '/recommendations',
      component: () => import('@/views/RecommendView.vue'),
      meta: { area: 'consumer', titleKey: 'nav.recommend', requiresAuth: true },
    },
    {
      path: '/orders',
      component: () => import('@/views/OrdersView.vue'),
      meta: { area: 'consumer', titleKey: 'nav.orders', requiresAuth: true },
    },
    {
      path: '/orders/:id/review',
      component: () => import('@/views/ReviewView.vue'),
      meta: { area: 'consumer', titleKey: 'common.review', requiresAuth: true },
    },
    {
      path: '/payment/:orderId?',
      component: () => import('@/views/PaymentView.vue'),
      meta: {
        area: 'consumer',
        titleKey: 'nav.payment',
        requiresAuth: true,
        hideConsumerBottomNav: true,
      },
    },
    {
      path: '/logistics/:orderId?',
      component: () => import('@/views/LogisticsView.vue'),
      meta: { area: 'consumer', titleKey: 'nav.logistics', requiresAuth: true },
    },
    {
      path: '/membership',
      component: () => import('@/views/MembershipView.vue'),
      meta: { area: 'consumer', titleKey: 'nav.membership', requiresAuth: true },
    },
    {
      path: '/cart',
      component: () => import('@/views/CartView.vue'),
      meta: { area: 'consumer', titleKey: 'nav.cart', requiresAuth: true },
    },
    {
      path: '/checkout',
      component: () => import('@/views/CheckoutView.vue'),
      meta: {
        area: 'consumer',
        titleKey: 'shop.checkout',
        requiresAuth: true,
        hideConsumerBottomNav: true,
      },
    },
    {
      path: '/profile',
      component: () => import('@/views/ProfileView.vue'),
      meta: { area: 'consumer', titleKey: 'nav.profile', requiresAuth: true },
    },
    {
      path: '/admin',
      component: () => import('@/views/AdminView.vue'),
      meta: { area: 'admin', titleKey: 'nav.admin', requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/inventory',
      component: () => import('@/views/InventoryView.vue'),
      meta: {
        area: 'admin',
        titleKey: 'nav.inventory',
        requiresAuth: true,
        requiresAdmin: true,
      },
    },
    {
      path: '/marketing',
      component: () => import('@/views/MarketingView.vue'),
      meta: {
        area: 'admin',
        titleKey: 'nav.marketing',
        requiresAuth: true,
        requiresAdmin: true,
      },
    },
    {
      path: '/risk',
      component: () => import('@/views/RiskReviewView.vue'),
      meta: { area: 'admin', titleKey: 'nav.risk', requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/dashboard',
      component: () => import('@/views/DashboardView.vue'),
      meta: {
        area: 'admin',
        titleKey: 'nav.dashboard',
        requiresAuth: true,
        requiresAdmin: true,
      },
    },
    {
      path: '/tenants',
      component: () => import('@/views/TenantAdminView.vue'),
      meta: { area: 'admin', titleKey: 'nav.tenants', requiresAuth: true, requiresAdmin: true },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { area: 'consumer', titleKey: 'common.notFound' },
    },
  ],
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    if (to.hash) {
      return { el: to.hash, behavior: 'smooth' }
    }
    return { top: 0 }
  },
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  const needsAuthDecision = requiresAuth(to) || requiresAdmin(to) || Boolean(to.meta.publicOnly)
  if (!auth.loaded) {
    if (needsAuthDecision) {
      await auth.loadCurrentUser()
    } else {
      void auth.loadCurrentUser()
    }
  }
  if (requiresAuth(to) && !auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (requiresAdmin(to) && !auth.isAdmin) {
    return { path: '/shop' }
  }
  if (
    auth.isLoggedIn &&
    auth.passwordChangeRequired &&
    to.path !== '/profile' &&
    to.path !== '/login' &&
    !to.path.startsWith('/api/')
  ) {
    return { path: '/profile', query: { forcePasswordChange: '1' } }
  }
  if (to.meta.publicOnly && auth.isLoggedIn) {
    const redirect = typeof to.query.redirect === 'string' ? to.query.redirect : null
    return redirect ? { path: redirect } : { path: auth.isAdmin ? '/admin' : '/shop' }
  }
  return true
})

router.afterEach(async (to) => {
  await nextTick()
  if (typeof document === 'undefined') {
    return
  }

  document.documentElement.lang = i18n.global.locale.value
  document.title = `${i18n.global.t(to.meta.titleKey)} | MonkeyShop`
  const focusTarget =
    document.querySelector<HTMLElement>('#page-title') ??
    document.querySelector<HTMLElement>('#main-content')
  focusTarget?.focus({ preventScroll: true })
})

router.onError((error) => {
  console.error('[router] navigation error:', error)
})

export default router

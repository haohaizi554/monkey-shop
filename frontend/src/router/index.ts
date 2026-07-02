import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
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
    { path: '/', redirect: '/shop' },
    {
      path: '/login',
      component: () => import('@/views/LoginView.vue'),
      meta: { publicOnly: true },
    },
    { path: '/shop', component: () => import('@/views/ShopView.vue') },
    { path: '/shop/:productId', component: () => import('@/views/ProductDetailView.vue') },
    {
      path: '/orders',
      component: () => import('@/views/OrdersView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/profile',
      component: () => import('@/views/ProfileView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/admin',
      component: () => import('@/views/AdminView.vue'),
      meta: { requiresAuth: true, requiresAdmin: true },
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
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
  if (to.meta.publicOnly && auth.isLoggedIn) {
    return { path: auth.isAdmin ? '/admin' : '/shop' }
  }
  return true
})

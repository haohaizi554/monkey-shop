export type RenderingMode = 'ssr' | 'csr'

export interface NuxtSsrRouteRule {
  path: string
  rendering: RenderingMode
  prerender: boolean
  jsonLd: boolean
  reason: string
}

export const nuxtSsrRouteRules: readonly NuxtSsrRouteRule[] = [
  {
    path: '/shop',
    rendering: 'ssr',
    prerender: true,
    jsonLd: true,
    reason: 'Public catalog needs product discovery, LCP budget, and Product JSON-LD.',
  },
  {
    path: '/shop/:productId',
    rendering: 'ssr',
    prerender: true,
    jsonLd: true,
    reason: 'Future product detail pages should render product metadata at the edge.',
  },
  {
    path: '/orders',
    rendering: 'csr',
    prerender: false,
    jsonLd: false,
    reason: 'Authenticated customer order state must remain client-only.',
  },
  {
    path: '/admin',
    rendering: 'csr',
    prerender: false,
    jsonLd: false,
    reason: 'Administrative operations stay behind auth and should not be indexed.',
  },
]

export const nuxtPrerenderRoutes = nuxtSsrRouteRules
  .filter((route) => route.rendering === 'ssr' && route.prerender)
  .map((route) => route.path)

export const nuxtSitemapDynamicSources = [
  {
    path: '/shop/:productId',
    source: '/api/v1/monkeys',
    idField: 'id',
    changefreq: 'daily',
    priority: 0.8,
  },
] as const

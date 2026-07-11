import 'vue-router'

export type RouteArea = 'consumer' | 'admin' | 'auth'

declare module 'vue-router' {
  interface RouteMeta {
    area: RouteArea
    titleKey: string
    requiresAuth?: boolean
    requiresAdmin?: boolean
    publicOnly?: boolean
  }
}

export {}

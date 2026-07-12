import { onScopeDispose, reactive, watch, type UnwrapNestedRefs } from 'vue'
import {
  useRoute,
  useRouter,
  type LocationQuery,
  type LocationQueryRaw,
  type LocationQueryValue,
} from 'vue-router'

export interface RouteQuerySchema<T extends object> {
  parse(query: LocationQuery): T
  serialize(value: T): LocationQueryRaw
}

export interface SearchRouteState {
  keyword: string
  category: string
  attribute: string
  value: string
  sort: 'RELEVANCE' | 'PRICE_ASC' | 'PRICE_DESC' | 'NEWEST' | 'HOT'
  page: number
  size: 12 | 24 | 48
}

export interface RouteQueryStateOptions {
  debounceMs?: number
}

const supportedSorts = new Set<SearchRouteState['sort']>([
  'RELEVANCE',
  'PRICE_ASC',
  'PRICE_DESC',
  'NEWEST',
  'HOT',
])
const supportedSizes = new Set<SearchRouteState['size']>([12, 24, 48])

function first(value: LocationQueryValue | LocationQueryValue[] | undefined): string {
  return Array.isArray(value) ? (value[0] ?? '') : (value ?? '')
}

export function parseSearchQuery(query: LocationQuery): SearchRouteState {
  const sortCandidate = first(query.sort).toUpperCase() as SearchRouteState['sort']
  const pageCandidate = Number.parseInt(first(query.page), 10)
  const sizeCandidate = Number.parseInt(first(query.size), 10) as SearchRouteState['size']

  return {
    keyword: first(query.q),
    category: first(query.category),
    attribute: first(query.attribute),
    value: first(query.value),
    sort: supportedSorts.has(sortCandidate) ? sortCandidate : 'RELEVANCE',
    page: Number.isFinite(pageCandidate) ? Math.max(0, pageCandidate) : 0,
    size: supportedSizes.has(sizeCandidate) ? sizeCandidate : 12,
  }
}

export function serializeSearchQuery(value: SearchRouteState): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  const keyword = value.keyword.trim()
  const category = value.category.trim()
  const attribute = value.attribute.trim()
  const attributeValue = value.value.trim()
  const page = Math.max(0, Math.trunc(value.page))

  if (keyword) query.q = keyword
  if (category) query.category = category
  if (attribute) query.attribute = attribute
  if (attributeValue) query.value = attributeValue
  if (value.sort !== 'RELEVANCE') query.sort = value.sort
  if (page > 0) query.page = String(page)
  if (value.size !== 12 && supportedSizes.has(value.size)) query.size = String(value.size)
  return query
}

export const searchRouteQuerySchema: RouteQuerySchema<SearchRouteState> = {
  parse: parseSearchQuery,
  serialize: serializeSearchQuery,
}

function querySignature(query: LocationQuery | LocationQueryRaw): string {
  return Object.keys(query)
    .sort()
    .map((key) => {
      const rawValue = query[key]
      const value = Array.isArray(rawValue) ? rawValue.join(',') : String(rawValue ?? '')
      return `${key}=${value}`
    })
    .join('&')
}

export function useRouteQueryState<T extends object>(
  schema: RouteQuerySchema<T>,
  options: RouteQueryStateOptions = {},
) {
  const route = useRoute()
  const router = useRouter()
  const state = reactive(schema.parse(route.query)) as UnwrapNestedRefs<T>
  const debounceMs = options.debounceMs ?? 250
  let applyingRoute = false
  let replaceTimer: ReturnType<typeof setTimeout> | undefined

  function clearScheduledReplace() {
    if (replaceTimer !== undefined) {
      clearTimeout(replaceTimer)
      replaceTimer = undefined
    }
  }

  async function replaceNow(): Promise<boolean> {
    clearScheduledReplace()
    const nextQuery = schema.serialize(state as unknown as T)
    if (querySignature(nextQuery) === querySignature(route.query)) {
      return false
    }
    await router.replace({ query: nextQuery })
    return true
  }

  function scheduleReplace() {
    clearScheduledReplace()
    replaceTimer = setTimeout(() => {
      replaceTimer = undefined
      void replaceNow().catch(() => undefined)
    }, debounceMs)
  }

  watch(
    state,
    () => {
      if (!applyingRoute) {
        scheduleReplace()
      }
    },
    { deep: true, flush: 'sync' },
  )

  watch(
    () => route.query,
    (query) => {
      clearScheduledReplace()
      applyingRoute = true
      Object.assign(state, schema.parse(query))
      applyingRoute = false
    },
    { deep: true, flush: 'sync' },
  )

  onScopeDispose(clearScheduledReplace)

  return { state, replaceNow }
}

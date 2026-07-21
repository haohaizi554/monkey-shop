import { request } from './http'
import type { PageEnvelope } from './page'
import type {
  CatalogPriceQuote,
  CatalogSpu,
  CategoryNode,
  Monkey,
  MonkeyRequest,
  UploadResponse,
} from '@/types'

export interface MonkeyPageQuery {
  page: number
  size: number
  sort?: string
  keyword?: string
  minPrice?: string | number
  maxPrice?: string | number
  inStock?: boolean
  signal?: AbortSignal
}

export function listMonkeyPage(query: MonkeyPageQuery): Promise<PageEnvelope<Monkey>> {
  const { signal, ...params } = query
  return request<PageEnvelope<Monkey>>({ url: '/monkeys', params, signal })
}

export function addMonkey(payload: MonkeyRequest): Promise<Monkey> {
  return request<Monkey>({ url: '/monkeys/add', method: 'POST', data: payload })
}

export function updateMonkey(payload: MonkeyRequest): Promise<Monkey> {
  return request<Monkey>({ url: '/monkeys/update', method: 'POST', data: payload })
}

export async function deleteMonkey(id: number): Promise<void> {
  await request<void>({ url: `/monkeys/${id}`, method: 'DELETE' })
}

export async function uploadImage(file: File, type: 'avatar' | 'product'): Promise<UploadResponse> {
  const form = new FormData()
  form.set('file', file)
  form.set('type', type)
  return request<UploadResponse>({ url: '/uploads', method: 'POST', data: form })
}

export function getCategoryTree(): Promise<CategoryNode[]> {
  return request<CategoryNode[]>({ url: '/catalog/categories/tree' })
}

export function flattenCategoryTree(nodes: CategoryNode[]): CategoryNode[] {
  return nodes.flatMap((node) => [node, ...flattenCategoryTree(node.children ?? [])])
}

export function getCatalogSpu(spuId: string | number, signal?: AbortSignal): Promise<CatalogSpu> {
  return request<CatalogSpu>({ url: `/catalog/spus/${spuId}`, signal })
}

export function getCatalogPrice(
  spuId: string | number,
  identity = 'ANONYMOUS',
  region = '',
  signal?: AbortSignal,
): Promise<CatalogPriceQuote> {
  return request<CatalogPriceQuote>({
    url: `/catalog/spus/${spuId}/price`,
    params: { identity, region },
    signal,
  })
}

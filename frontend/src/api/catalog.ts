import { request } from './http'
import type { ApiId } from './ids'
import type { PageEnvelope } from './page'
import type {
  CatalogPriceQuote,
  CatalogSpu,
  CatalogSpuWriteRequest,
  CategoryNode,
  Monkey,
  MonkeyRequest,
  ProductStatus,
  SearchProduct,
  SearchSort,
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

export interface CatalogProductPageQuery {
  page: number
  size: number
  sort?: SearchSort
  keyword?: string
  categoryId?: ApiId
  attributeKey?: string
  attributeValue?: string
  minPrice?: string | number
  maxPrice?: string | number
  inStock?: boolean
  signal?: AbortSignal
}

export interface CatalogManagementPageQuery {
  page: number
  size: number
  status?: ProductStatus
  keyword?: string
  signal?: AbortSignal
}

export function listCatalogSpuPage(
  query: CatalogManagementPageQuery,
): Promise<PageEnvelope<CatalogSpu>> {
  const { signal, ...params } = query
  return request<PageEnvelope<CatalogSpu>>({ url: '/catalog/spus', params, signal })
}

export function createCatalogSpu(payload: CatalogSpuWriteRequest): Promise<CatalogSpu> {
  return request<CatalogSpu>({ url: '/catalog/spus', method: 'POST', data: payload })
}

export function updateCatalogSpu(
  spuId: ApiId,
  payload: CatalogSpuWriteRequest,
): Promise<CatalogSpu> {
  return request<CatalogSpu>({ url: `/catalog/spus/${spuId}`, method: 'PUT', data: payload })
}

export function transitionCatalogSpuStatus(
  spuId: ApiId,
  targetStatus: ProductStatus,
): Promise<CatalogSpu> {
  return request<CatalogSpu>({
    url: `/catalog/spus/${spuId}/status`,
    method: 'POST',
    data: { targetStatus },
  })
}

export function retireCatalogSpu(spuId: ApiId): Promise<CatalogSpu> {
  return request<CatalogSpu>({ url: `/catalog/spus/${spuId}`, method: 'DELETE' })
}

export function listCatalogProductPage(
  query: CatalogProductPageQuery,
): Promise<PageEnvelope<SearchProduct>> {
  const { signal, ...params } = query
  return request<PageEnvelope<SearchProduct>>({ url: '/search/products', params, signal })
}

export function addMonkey(payload: MonkeyRequest): Promise<Monkey> {
  return request<Monkey>({ url: '/monkeys/add', method: 'POST', data: payload })
}

export function updateMonkey(payload: MonkeyRequest): Promise<Monkey> {
  return request<Monkey>({ url: '/monkeys/update', method: 'POST', data: payload })
}

export async function deleteMonkey(id: ApiId): Promise<void> {
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

export function getCatalogSpu(spuId: ApiId, signal?: AbortSignal): Promise<CatalogSpu> {
  return request<CatalogSpu>({ url: `/catalog/spus/${spuId}`, signal })
}

export function getCatalogPrice(
  spuId: ApiId,
  region = '',
  signal?: AbortSignal,
): Promise<CatalogPriceQuote> {
  return request<CatalogPriceQuote>({
    url: `/catalog/spus/${spuId}/price`,
    params: { region },
    signal,
  })
}

import { request } from './http'
import type {
  HotKeyword,
  Recommendation,
  SearchConversionRequest,
  SearchPage,
  SearchProfile,
  SearchProfileRequest,
  SearchQuery,
  SearchSuggestion,
} from '@/types'

export function searchProducts(params: SearchQuery): Promise<SearchPage> {
  return request<SearchPage>({ url: '/search/products', params })
}

export function searchSuggestions(keyword: string): Promise<SearchSuggestion[]> {
  return request<SearchSuggestion[]>({ url: '/search/suggestions', params: { keyword } })
}

export function hotKeywords(): Promise<HotKeyword[]> {
  return request<HotKeyword[]>({ url: '/search/hot' })
}

export function recommendations(): Promise<Recommendation[]> {
  return request<Recommendation[]>({ url: '/search/recommendations' })
}

export function updateSearchProfile(payload: SearchProfileRequest): Promise<SearchProfile> {
  return request<SearchProfile>({ url: '/search/profile', method: 'POST', data: payload })
}

export async function recordSearchConversion(payload: SearchConversionRequest): Promise<void> {
  await request<void>({ url: '/search/conversions', method: 'POST', data: payload })
}

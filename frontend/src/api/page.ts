import type { AxiosRequestConfig } from 'axios'
import { request } from './http'

export interface PageEnvelope<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

const maximumPageSize = 100

export async function requestAllPageContent<T>(config: AxiosRequestConfig): Promise<T[]> {
  const content: T[] = []
  let pageNumber = 0

  while (true) {
    const page = await request<PageEnvelope<T>>({
      ...config,
      params: { ...config.params, page: pageNumber, size: maximumPageSize },
    })
    content.push(...page.content)

    if (page.last || pageNumber + 1 >= page.totalPages) {
      return content
    }
    pageNumber += 1
  }
}

import { request } from './http'
import type { Stats } from '@/types'

export function stats(params?: { start?: string; end?: string }): Promise<Stats> {
  return request<Stats>({
    url: '/stats/data',
    params,
  })
}

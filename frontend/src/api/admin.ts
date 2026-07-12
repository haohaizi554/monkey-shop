import { request } from './http'
import type { Stats } from '@/types'

export interface AuditTraceEvent {
  id: string
  eventType: string
  userId: string | null
  description: string
  createdAt: string
  traceId: string
}

export function stats(params?: { start?: string; end?: string }): Promise<Stats> {
  return request<Stats>({
    url: '/stats/data',
    params,
  })
}

export function auditTrace(traceId: string): Promise<AuditTraceEvent[]> {
  return request<AuditTraceEvent[]>({
    url: '/stats/audit-trace',
    params: { traceId },
  })
}

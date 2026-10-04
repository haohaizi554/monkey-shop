import { request } from './http'
import { normalizeApiId, parsePositiveApiId, type ApiId } from './ids'
import type { Stats } from '@/types'

export interface AuditTraceEvent {
  id: ApiId
  eventType: string
  outcome: string
  actorUserId: ApiId | null
  actorRole: string | null
  subjectHash: string | null
  sourceIp: string | null
  createdAt: string
  traceId: string | null
  detail: string | null
}

function normalizeAuditTraceEvent(event: AuditTraceEvent): AuditTraceEvent {
  return {
    ...event,
    id: parsePositiveApiId(event.id) ?? normalizeApiId(event.id),
    actorUserId:
      event.actorUserId == null
        ? null
        : (parsePositiveApiId(event.actorUserId) ?? normalizeApiId(event.actorUserId)),
  }
}

export function auditTraceEventText(event: AuditTraceEvent): string {
  return [
    event.detail,
    event.actorUserId == null ? null : `user ${normalizeApiId(event.actorUserId)}`,
    event.traceId ? `trace ${event.traceId}` : null,
  ]
    .filter((value): value is string => Boolean(value))
    .join(' ')
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
  }).then((events) => events.map(normalizeAuditTraceEvent))
}

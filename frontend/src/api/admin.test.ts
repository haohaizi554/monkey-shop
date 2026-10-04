import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/http', () => ({
  request: requestMock,
}))

import { auditTrace, auditTraceEventText } from '@/api/admin'

describe('admin audit trace contract', () => {
  beforeEach(() => {
    requestMock.mockReset()
  })

  it('renders the backend actor and detail fields at runtime', async () => {
    const event = {
      id: '338329504114688002',
      eventType: 'PAYMENT_CREATED',
      outcome: 'SUCCESS',
      actorUserId: '338329504114688001',
      actorRole: 'ADMIN',
      subjectHash: 'subject-hash',
      sourceIp: '127.0.0.1',
      traceId: 'trace-snowflake',
      detail: 'Payment created for order',
      createdAt: '2026-08-28T00:00:00Z',
    }
    requestMock.mockResolvedValue([event])

    const [mapped] = await auditTrace('trace-snowflake')

    expect(mapped).toMatchObject({
      id: event.id,
      actorUserId: event.actorUserId,
      detail: event.detail,
    })
    expect(auditTraceEventText(mapped)).toContain(event.detail)
    expect(auditTraceEventText(mapped)).toContain(event.actorUserId)
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => vi.fn().mockResolvedValue({}))

vi.mock('@/api/http', () => ({
  request: requestMock,
}))

import { adminReviewIdentity, submitIdentity } from '@/api/membership'

describe('membership identity API', () => {
  beforeEach(() => {
    requestMock.mockClear()
  })

  it('keeps identity submission and administrator review as separate API actions', async () => {
    await submitIdentity({ realName: 'Alice', idCardNo: 'masked-in-request' })
    expect(requestMock).toHaveBeenLastCalledWith({
      url: '/membership/identity',
      method: 'POST',
      data: { realName: 'Alice', idCardNo: 'masked-in-request' },
    })

    await adminReviewIdentity(7, {
      status: 'VERIFIED',
      reason: 'matched records',
      totpCode: '123456',
    })
    expect(requestMock).toHaveBeenLastCalledWith({
      url: '/membership/admin/7/identity/review',
      method: 'POST',
      data: { status: 'VERIFIED', reason: 'matched records', totpCode: '123456' },
    })
  })
})

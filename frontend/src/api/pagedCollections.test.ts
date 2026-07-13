import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/http', () => ({
  request: requestMock,
}))

import { listMonkeys } from '@/api/catalog'
import { allOrders, myOrders } from '@/api/orders'
import { addresses } from '@/api/user'

function page<T>(content: T[], pageNumber = 0, totalPages = 1) {
  return {
    content,
    page: pageNumber,
    size: 100,
    totalElements: totalPages === 1 ? content.length : 2,
    totalPages,
    first: pageNumber === 0,
    last: pageNumber + 1 >= totalPages,
  }
}

describe('paged collection API compatibility', () => {
  beforeEach(() => {
    requestMock.mockReset()
  })

  it('keeps existing list callers on arrays while the backend returns page envelopes', async () => {
    requestMock
      .mockResolvedValueOnce(page([{ id: 1, nickname: 'Momo' }], 0, 2))
      .mockResolvedValueOnce(page([{ id: 5, nickname: 'Kiki' }], 1, 2))
      .mockResolvedValueOnce(page([{ id: 2, orderNo: 'ORD-2' }]))
      .mockResolvedValueOnce(page([{ id: 3, orderNo: 'ORD-3' }]))
      .mockResolvedValueOnce(page([{ id: 4, recipientName: 'Ada' }]))

    await expect(listMonkeys()).resolves.toEqual([
      { id: 1, nickname: 'Momo' },
      { id: 5, nickname: 'Kiki' },
    ])
    await expect(myOrders()).resolves.toEqual([{ id: 2, orderNo: 'ORD-2' }])
    await expect(allOrders()).resolves.toEqual([{ id: 3, orderNo: 'ORD-3' }])
    await expect(addresses()).resolves.toEqual([{ id: 4, recipientName: 'Ada' }])

    expect(requestMock.mock.calls.map(([config]) => config.url)).toEqual([
      '/monkeys',
      '/monkeys',
      '/orders/my',
      '/orders/all',
      '/addresses',
    ])
    expect(requestMock.mock.calls.slice(0, 2).map(([config]) => config.params)).toEqual([
      { page: 0, size: 100 },
      { page: 1, size: 100 },
    ])
  })
})

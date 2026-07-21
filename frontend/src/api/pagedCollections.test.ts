import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/http', () => ({
  request: requestMock,
}))

import { listMonkeyPage, listMonkeys } from '@/api/catalog'
import { allOrderPage, allOrders, myOrderPage, myOrders } from '@/api/orders'
import { addressPage, addresses } from '@/api/user'

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

  it('exposes one-page contracts without silently walking the remaining pages', async () => {
    const controller = new AbortController()
    requestMock
      .mockResolvedValueOnce(page([{ id: 11, nickname: 'Page monkey' }], 2, 5))
      .mockResolvedValueOnce(page([{ id: 12, orderNo: 'MY-12' }], 1, 4))
      .mockResolvedValueOnce(page([{ id: 13, orderNo: 'ADMIN-13' }], 3, 8))
      .mockResolvedValueOnce(page([{ id: 14, recipientName: 'Grace' }], 0, 2))

    await expect(
      listMonkeyPage({
        page: 2,
        size: 12,
        sort: 'name,asc',
        keyword: 'golden',
        inStock: true,
        signal: controller.signal,
      }),
    ).resolves.toMatchObject({ page: 2, totalPages: 5 })
    await expect(
      myOrderPage({ page: 1, size: 10, status: 'PAID', signal: controller.signal }),
    ).resolves.toMatchObject({ page: 1, totalPages: 4 })
    await expect(
      allOrderPage({ page: 3, size: 25, keyword: 'ADMIN-13', signal: controller.signal }),
    ).resolves.toMatchObject({ page: 3, totalPages: 8 })
    await expect(
      addressPage({ page: 0, size: 20, signal: controller.signal }),
    ).resolves.toMatchObject({ page: 0, totalPages: 2 })

    expect(requestMock).toHaveBeenCalledTimes(4)
    expect(requestMock.mock.calls.map(([config]) => config)).toEqual([
      {
        url: '/monkeys',
        params: {
          page: 2,
          size: 12,
          sort: 'name,asc',
          keyword: 'golden',
          inStock: true,
        },
        signal: controller.signal,
      },
      {
        url: '/orders/my',
        params: { page: 1, size: 10, status: 'PAID' },
        signal: controller.signal,
      },
      {
        url: '/orders/all',
        params: { page: 3, size: 25, keyword: 'ADMIN-13' },
        signal: controller.signal,
      },
      {
        url: '/addresses',
        params: { page: 0, size: 20 },
        signal: controller.signal,
      },
    ])
  })
})

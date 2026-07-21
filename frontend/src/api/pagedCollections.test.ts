import { beforeEach, describe, expect, it, vi } from 'vitest'

const requestMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/http', () => ({
  request: requestMock,
}))

import { listMonkeyPage } from '@/api/catalog'
import { allOrderPage, myOrderPage } from '@/api/orders'
import { addressPage } from '@/api/user'

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

describe('paged collection API contracts', () => {
  beforeEach(() => {
    requestMock.mockReset()
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

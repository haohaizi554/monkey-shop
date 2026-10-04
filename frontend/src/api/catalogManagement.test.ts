import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from './http'
import {
  createCatalogSpu,
  listCatalogSpuPage,
  retireCatalogSpu,
  transitionCatalogSpuStatus,
  updateCatalogSpu,
} from './catalog'

vi.mock('./http', () => ({ request: vi.fn() }))

describe('canonical catalog management API', () => {
  beforeEach(() => {
    vi.mocked(request).mockReset()
    vi.mocked(request).mockResolvedValue({})
  })

  it('lists all tenant catalog states with the supplied management filters', async () => {
    await listCatalogSpuPage({ page: 0, size: 20, status: 'DRAFT', keyword: 'phone' })

    expect(request).toHaveBeenCalledWith({
      url: '/catalog/spus',
      params: { page: 0, size: 20, status: 'DRAFT', keyword: 'phone' },
    })
  })

  it('uses canonical create, update, status and retire routes', async () => {
    await createCatalogSpu({} as never)
    await updateCatalogSpu('9007199254740993', {} as never)
    await transitionCatalogSpuStatus('9007199254740993', 'UNLISTED')
    await retireCatalogSpu('9007199254740993')

    expect(request).toHaveBeenNthCalledWith(1, { url: '/catalog/spus', method: 'POST', data: {} })
    expect(request).toHaveBeenNthCalledWith(2, {
      url: '/catalog/spus/9007199254740993',
      method: 'PUT',
      data: {},
    })
    expect(request).toHaveBeenNthCalledWith(3, {
      url: '/catalog/spus/9007199254740993/status',
      method: 'POST',
      data: { targetStatus: 'UNLISTED' },
    })
    expect(request).toHaveBeenNthCalledWith(4, {
      url: '/catalog/spus/9007199254740993',
      method: 'DELETE',
    })
  })
})

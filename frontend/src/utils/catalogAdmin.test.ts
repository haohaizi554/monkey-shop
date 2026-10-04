import { describe, expect, it } from 'vitest'
import {
  allowedCatalogStatusTargets,
  catalogAdminFormPayload,
  catalogToAdminForm,
  emptyCatalogAdminForm,
} from './catalogAdmin'

describe('canonical catalog admin form', () => {
  it('preserves an unsafe-to-number Java Long as a string in update payloads', () => {
    const form = emptyCatalogAdminForm()
    form.categoryId = '9007199254740993'
    form.shopId = '9007199254740995'
    form.name = 'Phone'
    form.title = 'Phone'
    form.originalPrice = '99.00'

    const payload = catalogAdminFormPayload(form)

    expect(payload.categoryId).toBe('9007199254740993')
    expect(payload.shopId).toBe('9007199254740995')
  })

  it('converts SKU specifications into editable dimensions without inventing stock', () => {
    const form = catalogToAdminForm({
      id: '11',
      categoryId: '12',
      shopId: '13',
      name: 'Phone',
      title: 'Phone',
      status: 'DRAFT',
      originalPrice: '99.00',
      regionPrices: {},
      attributes: {},
      skus: [
        {
          id: '14',
          spuId: '11',
          skuCode: 'SKU-14',
          specification: { color: 'Black' },
          originalPrice: '99.00',
          regionPrices: {},
          active: true,
        },
      ],
    })

    expect(JSON.parse(form.specificationsJson)).toEqual({ color: ['Black'] })
    expect(form).not.toHaveProperty('stock')
  })

  it('only offers state-machine-approved status targets', () => {
    expect(allowedCatalogStatusTargets('LISTED')).toEqual(['UNLISTED'])
    expect(allowedCatalogStatusTargets('RECYCLED')).toEqual([])
  })
})

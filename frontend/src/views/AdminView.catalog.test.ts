import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const source = readFileSync('src/views/AdminView.vue', 'utf8')

describe('admin catalog ownership', () => {
  it('uses the canonical catalog management API instead of legacy monkey CRUD', () => {
    expect(source).toContain('listCatalogSpuPage')
    expect(source).toContain('createCatalogSpu')
    expect(source).toContain('updateCatalogSpu')
    expect(source).toContain('retireCatalogSpu')
    expect(source).toContain('transitionCatalogSpuStatus')
    expect(source).not.toContain('listMonkeyPage')
    expect(source).not.toContain('addMonkey')
    expect(source).not.toContain('updateMonkey')
    expect(source).not.toContain('deleteMonkey')
  })

  it('does not expose a scalar stock field for canonical SKU inventory', () => {
    expect(source).toContain("t('admin.catalogInventoryHint')")
    expect(source).not.toContain('productForm.stock')
  })
})

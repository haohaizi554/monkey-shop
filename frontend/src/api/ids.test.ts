import { describe, expect, it } from 'vitest'
import { isPositiveApiId, normalizeApiId, sameApiId } from './ids'

describe('API ID boundaries', () => {
  it('rejects an unsafe Java Long represented as a lossy JavaScript number', () => {
    expect(isPositiveApiId(Number('338329504114688001'))).toBe(false)
  })

  it('keeps decimal strings exact when normalizing and comparing IDs', () => {
    const id = '338329504114688001'

    expect(normalizeApiId(id)).toBe(id)
    expect(sameApiId(id, id)).toBe(true)
  })
})

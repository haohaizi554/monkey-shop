import { describe, expect, it } from 'vitest'
import type { LocationQuery } from 'vue-router'
import { parseSearchQuery, serializeSearchQuery, type SearchRouteState } from './useRouteQueryState'

describe('search route query state', () => {
  it('serializes only non-default discovery state', () => {
    const value: SearchRouteState = {
      keyword: ' golden ',
      category: '',
      attribute: '',
      value: '',
      sort: 'PRICE_ASC',
      page: 2,
      size: 12,
    }

    expect(serializeSearchQuery(value)).toEqual({
      q: 'golden',
      sort: 'PRICE_ASC',
      page: '2',
    })
  })

  it('parses arrays and normalizes unsupported values', () => {
    const query = {
      q: ['golden', 'ignored'],
      category: '7',
      attribute: 'coat',
      value: 'silky',
      sort: 'not-a-sort',
      page: '-8',
      size: '99',
    } as LocationQuery

    expect(parseSearchQuery(query)).toEqual({
      keyword: 'golden',
      category: '7',
      attribute: 'coat',
      value: 'silky',
      sort: 'RELEVANCE',
      page: 0,
      size: 12,
    })
  })

  it('round-trips supported discovery state', () => {
    const value: SearchRouteState = {
      keyword: 'silver',
      category: '3',
      attribute: 'temperament',
      value: 'calm',
      sort: 'PRICE_DESC',
      page: 4,
      size: 24,
    }

    expect(parseSearchQuery(serializeSearchQuery(value) as LocationQuery)).toEqual(value)
  })

  it('accepts supported sort and size values case-insensitively', () => {
    expect(parseSearchQuery({ sort: 'newest', size: '48' } as LocationQuery)).toMatchObject({
      sort: 'NEWEST',
      size: 48,
    })
  })
})

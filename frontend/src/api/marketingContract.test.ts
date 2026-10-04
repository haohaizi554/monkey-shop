import { describe, expectTypeOf, it } from 'vitest'
import type { ApiId } from './ids'
import type {
  MarketingPriceAllocation,
  MarketingPriceLine,
  MarketingPriceQuote,
  MarketingPriceRequest,
} from '@/types'

describe('marketing price contract', () => {
  it('models line-aware request and allocation response IDs without narrowing Java Longs', () => {
    expectTypeOf<MarketingPriceRequest['lines']>().toEqualTypeOf<MarketingPriceLine[] | undefined>()
    expectTypeOf<MarketingPriceLine['lineId']>().toEqualTypeOf<ApiId>()
    expectTypeOf<MarketingPriceLine['categoryId']>().toEqualTypeOf<ApiId | undefined>()
    expectTypeOf<MarketingPriceLine['shopId']>().toEqualTypeOf<ApiId | undefined>()
    expectTypeOf<MarketingPriceQuote['allocations']>().toEqualTypeOf<MarketingPriceAllocation[]>()
    expectTypeOf<MarketingPriceAllocation['lineId']>().toEqualTypeOf<ApiId>()
  })
})

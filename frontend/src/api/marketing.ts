import { request } from './http'
import type {
  CouponClaimRequest,
  CouponRedeemRequest,
  CouponReturnRequest,
  CouponWalletEntry,
  GroupBuyJoinRequest,
  GroupBuyTeam,
  MarketingPriceQuote,
  MarketingPriceRequest,
  SeckillOrder,
  SeckillRequest,
} from '@/types'

export function claimCoupon(requestBody: CouponClaimRequest): Promise<CouponWalletEntry> {
  return request<CouponWalletEntry>({
    url: '/marketing/coupons/claim',
    method: 'POST',
    data: requestBody,
  })
}

export function redeemCoupon(requestBody: CouponRedeemRequest): Promise<CouponWalletEntry> {
  return request<CouponWalletEntry>({
    url: '/marketing/coupons/redeem',
    method: 'POST',
    data: requestBody,
  })
}

export function returnCoupon(requestBody: CouponReturnRequest): Promise<CouponWalletEntry> {
  return request<CouponWalletEntry>({
    url: '/marketing/coupons/return',
    method: 'POST',
    data: requestBody,
  })
}

export function quoteMarketingPrice(
  requestBody: MarketingPriceRequest,
): Promise<MarketingPriceQuote> {
  return request<MarketingPriceQuote>({
    url: '/marketing/price/quote',
    method: 'POST',
    data: requestBody,
  })
}

export function createSeckillOrder(requestBody: SeckillRequest): Promise<SeckillOrder> {
  return request<SeckillOrder>({
    url: '/marketing/seckill-orders',
    method: 'POST',
    data: requestBody,
  })
}

export function joinGroupBuy(requestBody: GroupBuyJoinRequest): Promise<GroupBuyTeam> {
  return request<GroupBuyTeam>({
    url: '/marketing/group-buy/join',
    method: 'POST',
    data: requestBody,
  })
}

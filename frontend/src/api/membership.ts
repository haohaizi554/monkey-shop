import { request } from './http'
import type {
  BrowseRecordRequest,
  CheckInResponse,
  CollectionRequest,
  LevelChangeRequest,
  MemberCollection,
  MembershipDashboard,
  PointsEarnRequest,
  PointsLedgerEntry,
  PointsRedeemRequest,
  PriceDropScanResult,
  RealNameVerifyRequest,
} from '@/types'

export function membershipDashboard(): Promise<MembershipDashboard> {
  return request<MembershipDashboard>({ url: '/membership/dashboard' })
}

export function verifyIdentity(payload: RealNameVerifyRequest): Promise<MembershipDashboard> {
  return request<MembershipDashboard>({
    url: '/membership/identity',
    method: 'POST',
    data: payload,
  })
}

export function checkIn(): Promise<CheckInResponse> {
  return request<CheckInResponse>({
    url: '/membership/check-in',
    method: 'POST',
  })
}

export function earnPoints(payload: PointsEarnRequest): Promise<PointsLedgerEntry> {
  return request<PointsLedgerEntry>({
    url: '/membership/points/earn',
    method: 'POST',
    data: payload,
  })
}

export function redeemPoints(payload: PointsRedeemRequest): Promise<PointsLedgerEntry> {
  return request<PointsLedgerEntry>({
    url: '/membership/points/redeem',
    method: 'POST',
    data: payload,
  })
}

export function changeLevel(payload: LevelChangeRequest): Promise<MembershipDashboard> {
  return request<MembershipDashboard>({
    url: '/membership/level',
    method: 'POST',
    data: payload,
  })
}

export function addCollection(payload: CollectionRequest): Promise<MemberCollection> {
  return request<MemberCollection>({
    url: '/membership/collections',
    method: 'POST',
    data: payload,
  })
}

export function removeCollection(productId: number): Promise<void> {
  return request<void>({
    url: `/membership/collections/${productId}`,
    method: 'DELETE',
  })
}

export function recordBrowse(payload: BrowseRecordRequest): Promise<void> {
  return request<void>({
    url: '/membership/browse',
    method: 'POST',
    data: payload,
  })
}

export function scanPriceDrops(): Promise<PriceDropScanResult> {
  return request<PriceDropScanResult>({
    url: '/membership/price-drops/scan',
    method: 'POST',
  })
}

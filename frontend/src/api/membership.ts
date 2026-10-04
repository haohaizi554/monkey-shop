import { request } from './http'
import type { ApiId } from './ids'
import type {
  BrowseRecordRequest,
  CheckInResponse,
  CollectionRequest,
  IdentityReviewRequest,
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

export function adminMembershipDashboard(userId: ApiId): Promise<MembershipDashboard> {
  return request<MembershipDashboard>({ url: `/membership/admin/${userId}/dashboard` })
}

export function submitIdentity(payload: RealNameVerifyRequest): Promise<MembershipDashboard> {
  return request<MembershipDashboard>({
    url: '/membership/identity',
    method: 'POST',
    data: payload,
  })
}

/**
 * Kept as a source-compatible alias. POST /identity submits a review request;
 * it does not perform identity verification locally.
 */
export const verifyIdentity = submitIdentity

export function adminReviewIdentity(
  userId: ApiId,
  payload: IdentityReviewRequest,
): Promise<MembershipDashboard> {
  return request<MembershipDashboard>({
    url: `/membership/admin/${userId}/identity/review`,
    method: 'POST',
    data: payload,
  })
}

export function checkIn(idempotencyKey: string): Promise<CheckInResponse> {
  return request<CheckInResponse>({
    url: '/membership/check-in',
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function adminEarnPoints(
  userId: ApiId,
  payload: PointsEarnRequest,
  idempotencyKey: string,
): Promise<PointsLedgerEntry> {
  return request<PointsLedgerEntry>({
    url: `/membership/admin/${userId}/points/earn`,
    method: 'POST',
    data: payload,
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function redeemPoints(
  payload: PointsRedeemRequest,
  idempotencyKey: string,
): Promise<PointsLedgerEntry> {
  return request<PointsLedgerEntry>({
    url: '/membership/points/redeem',
    method: 'POST',
    data: payload,
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function adminChangeLevel(
  userId: ApiId,
  payload: LevelChangeRequest,
): Promise<MembershipDashboard> {
  return request<MembershipDashboard>({
    url: `/membership/admin/${userId}/level`,
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

export function removeCollection(productId: ApiId): Promise<void> {
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

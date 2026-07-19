import { request } from './http'
import type {
  ProductProfile,
  RealtimeDashboard,
  TrackingEventRequest,
  TrackingEventResponse,
  UserProfileTag,
} from '@/types'

export function recordTrackingEvent(payload: TrackingEventRequest): Promise<TrackingEventResponse> {
  return request<TrackingEventResponse>({
    url: '/tracking/events',
    method: 'POST',
    data: payload,
    headers: payload.traceId ? { 'X-Trace-Id': payload.traceId } : undefined,
  })
}

export function trackingDashboard(minutes = 5): Promise<RealtimeDashboard> {
  return request<RealtimeDashboard>({ url: '/tracking/dashboard', params: { minutes } })
}

export function currentTrackingProfile(): Promise<UserProfileTag> {
  return request<UserProfileTag>({ url: '/tracking/profile/me' })
}

export function trackingProductProfile(productId: number): Promise<ProductProfile> {
  return request<ProductProfile>({ url: `/tracking/products/${productId}` })
}

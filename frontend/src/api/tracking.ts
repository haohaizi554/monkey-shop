import { request } from './http'
import type { ApiId } from './ids'
import type {
  ClientTrackingEventType,
  ProductProfile,
  RealtimeDashboard,
  TrackingEventRequest,
  TrackingEventResponse,
  UserProfileTag,
} from '@/types'

const AUTHORITATIVE_COMMERCE_EVENTS = new Set(['ORDER_CREATED', 'PAYMENT_SUCCESS'])

function isClientTrackingEventType(value: unknown): value is ClientTrackingEventType {
  return typeof value === 'string' && !AUTHORITATIVE_COMMERCE_EVENTS.has(value)
}

export function recordTrackingEvent(payload: TrackingEventRequest): Promise<TrackingEventResponse> {
  if (!isClientTrackingEventType(payload.eventType)) {
    return Promise.reject(
      new Error('Authoritative commerce events cannot be submitted through public tracking'),
    )
  }
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

export function trackingProductProfile(productId: ApiId): Promise<ProductProfile> {
  return request<ProductProfile>({ url: `/tracking/products/${productId}` })
}

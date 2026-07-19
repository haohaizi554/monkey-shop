import { request } from './http'
import type {
  AddressParseRequest,
  FreightQuoteRequest,
  FreightQuoteResponse,
  LogisticsTracking,
  ParsedAddress,
} from '@/types'

export function logisticsForOrder(orderId: number): Promise<LogisticsTracking> {
  return request<LogisticsTracking>({ url: `/logistics/orders/${orderId}` })
}

export function logisticsByTrackingNo(trackingNo: string): Promise<LogisticsTracking> {
  return request<LogisticsTracking>({ url: `/logistics/tracking/${trackingNo}` })
}

export function quoteFreight(payload: FreightQuoteRequest): Promise<FreightQuoteResponse> {
  return request<FreightQuoteResponse>({
    url: '/logistics/freight/quote',
    method: 'POST',
    data: payload,
  })
}

export function parseAddress(payload: AddressParseRequest): Promise<ParsedAddress> {
  return request<ParsedAddress>({
    url: '/logistics/address/parse',
    method: 'POST',
    data: payload,
  })
}

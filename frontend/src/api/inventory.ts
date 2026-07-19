import { request } from './http'
import type {
  InventoryReconciliation,
  InventoryReservation,
  InventoryReserveRequest,
  WarehouseStock,
} from '@/types'

export function inventoryStocks(skuId: number): Promise<WarehouseStock[]> {
  return request<WarehouseStock[]>({ url: `/inventory/skus/${skuId}/stocks` })
}

export function reserveInventory(
  requestBody: InventoryReserveRequest,
): Promise<InventoryReservation> {
  return request<InventoryReservation>({
    url: '/inventory/reservations',
    method: 'POST',
    data: requestBody,
  })
}

export function releaseInventory(reservationKey: string): Promise<InventoryReservation> {
  return request<InventoryReservation>({
    url: `/inventory/reservations/${reservationKey}/release`,
    method: 'POST',
  })
}

export function reconcileInventory(): Promise<InventoryReconciliation> {
  return request<InventoryReconciliation>({ url: '/inventory/reconciliation' })
}

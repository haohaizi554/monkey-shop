import { request } from './http'
import type {
  InventoryCompensateRequest,
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

export function deductInventory(reservationKey: string): Promise<InventoryReservation> {
  return request<InventoryReservation>({
    url: `/inventory/reservations/${reservationKey}/deduct`,
    method: 'POST',
  })
}

export function compensateInventory(
  requestBody: InventoryCompensateRequest,
): Promise<WarehouseStock> {
  return request<WarehouseStock>({
    url: '/inventory/compensations',
    method: 'POST',
    data: requestBody,
  })
}

export function reconcileInventory(): Promise<InventoryReconciliation> {
  return request<InventoryReconciliation>({ url: '/inventory/reconciliation' })
}

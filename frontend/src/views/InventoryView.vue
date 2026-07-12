<script setup lang="ts">
import { CircleCheck, Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import {
  inventoryStocks,
  reconcileInventory,
  releaseInventory,
  reserveInventory,
} from '@/api/inventory'
import AdminPageToolbar from '@/components/admin/AdminPageToolbar.vue'
import MetricStrip, { type MetricItem } from '@/components/admin/MetricStrip.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import { useRouteQueryState, type RouteQuerySchema } from '@/composables/useRouteQueryState'
import type {
  InventoryDiscrepancy,
  InventoryReconciliation,
  InventoryReservation,
  WarehouseStock,
} from '@/types'

defineOptions({ name: 'InventoryView' })

interface InventoryQuery {
  skuId: number | null
  region: string
}

type ReservationStatus = InventoryReservation['status']

const inventoryQuerySchema: RouteQuerySchema<InventoryQuery> = {
  parse(query: LocationQuery) {
    const rawSku = Array.isArray(query.skuId) ? query.skuId[0] : query.skuId
    const parsedSku = Number.parseInt(rawSku ?? '', 10)
    const rawRegion = Array.isArray(query.region) ? query.region[0] : query.region
    return {
      skuId: Number.isInteger(parsedSku) && parsedSku > 0 ? parsedSku : null,
      region: String(rawRegion ?? '').trim(),
    }
  },
  serialize(value: InventoryQuery): LocationQueryRaw {
    const query: LocationQueryRaw = {}
    if (value.skuId) query.skuId = String(value.skuId)
    if (value.region.trim()) query.region = value.region.trim()
    return query
  },
}

const { t } = useI18n()
const notify = useNotify()
const { state: query, replaceNow } = useRouteQueryState(inventoryQuerySchema, { debounceMs: 250 })
const stocksState = useAsyncState<WarehouseStock[]>({ preserveData: true })
const reconciliationState = useAsyncState<InventoryReconciliation>({ preserveData: true })
const reservations = ref<InventoryReservation[]>([])
const reservationKey = ref('')
const reserveQuantity = ref(1)
const reservationError = ref('')
const pendingKeys = ref(new Set<string>())
let stockLoadTimer: ReturnType<typeof setTimeout> | undefined

const stocks = computed(() => stocksState.data.value ?? [])
const normalizedRegion = computed(() => query.region.trim().toLocaleLowerCase())
const filteredStocks = computed(() => {
  if (!normalizedRegion.value) return stocks.value
  return stocks.value.filter((stock) => {
    const haystack = `${stock.province ?? ''} ${stock.warehouseCode ?? ''}`.toLocaleLowerCase()
    return haystack.includes(normalizedRegion.value)
  })
})
const totalAvailable = computed(() =>
  filteredStocks.value.reduce((sum, stock) => sum + stock.availableQuantity, 0),
)
const totalLocked = computed(() =>
  filteredStocks.value.reduce((sum, stock) => sum + stock.lockedQuantity, 0),
)
const reconciliation = computed(() => reconciliationState.data.value)
const discrepancies = computed(() => reconciliation.value?.discrepancies ?? [])
const metrics = computed<MetricItem[]>(() => [
  {
    key: 'available',
    label: t('inventory.available'),
    value: totalAvailable.value,
    tone: 'success',
  },
  { key: 'locked', label: t('inventory.locked'), value: totalLocked.value, tone: 'warning' },
  {
    key: 'reconciliation',
    label: t('inventory.reconcileStatus'),
    value:
      reconciliation.value?.balanced === true
        ? t('inventory.balanced')
        : reconciliation.value?.balanced === false
          ? t('inventory.discrepant')
          : '-',
    tone: reconciliation.value?.balanced === false ? 'danger' : 'neutral',
  },
])

const reservationStatusLabels: Record<ReservationStatus, string> = {
  RESERVED: 'reserved',
  RELEASED: 'released',
  DEDUCTED: 'deducted',
  EXPIRED: 'expired',
}

function reservationStatusLabel(status: ReservationStatus): string {
  return t(`inventory.status.${reservationStatusLabels[status] ?? 'reserved'}`)
}

function reservationStatusType(status: ReservationStatus): 'success' | 'warning' | 'info' {
  if (status === 'RESERVED') return 'warning'
  if (status === 'DEDUCTED') return 'success'
  return 'info'
}

function isPending(key: string): boolean {
  return pendingKeys.value.has(key)
}

function setPending(key: string, value: boolean) {
  const next = new Set(pendingKeys.value)
  if (value) next.add(key)
  else next.delete(key)
  pendingKeys.value = next
}

function patchStock(nextStock: WarehouseStock) {
  const rows = stocksState.data.value
  if (!rows) return
  const index = rows.findIndex(
    (stock) => stock.skuId === nextStock.skuId && stock.warehouseId === nextStock.warehouseId,
  )
  if (index >= 0) rows.splice(index, 1, nextStock)
  else rows.push(nextStock)
}

function patchReservation(nextReservation: InventoryReservation) {
  const index = reservations.value.findIndex(
    (reservation) => reservation.reservationKey === nextReservation.reservationKey,
  )
  if (index >= 0) reservations.value.splice(index, 1, nextReservation)
  else reservations.value.unshift(nextReservation)
  patchStock(nextReservation.stock)
}

function mergeDiscrepancies(
  previous: InventoryDiscrepancy[],
  next: InventoryDiscrepancy[],
): InventoryDiscrepancy[] {
  const nextKeys = new Set(next.map((row) => `${row.skuId}:${row.warehouseId}`))
  const merged = previous.filter((row) => nextKeys.has(`${row.skuId}:${row.warehouseId}`))
  for (const row of next) {
    const key = `${row.skuId}:${row.warehouseId}`
    const index = merged.findIndex((candidate) => `${candidate.skuId}:${candidate.warehouseId}` === key)
    if (index >= 0) merged.splice(index, 1, row)
    else merged.push(row)
  }
  return merged
}

async function loadStocks() {
  if (!query.skuId) {
    stocksState.reset()
    return
  }
  const skuId = query.skuId
  await stocksState.load(() => inventoryStocks(skuId), {
    preserveData: true,
    isEmpty: (rows) => rows.length === 0,
  })
}

function scheduleStockLoad() {
  if (stockLoadTimer) clearTimeout(stockLoadTimer)
  stockLoadTimer = setTimeout(() => {
    stockLoadTimer = undefined
    void loadStocks()
  }, 250)
}

async function searchStocks() {
  if (stockLoadTimer) {
    clearTimeout(stockLoadTimer)
    stockLoadTimer = undefined
  }
  await replaceNow()
  await loadStocks()
}

async function reserveCurrentSku() {
  const keyValue = reservationKey.value.trim()
  reservationError.value = ''
  if (!query.skuId || !keyValue) {
    reservationError.value = t('inventory.reservationKeyRequired')
    return
  }
  const pendingKey = `reserve:${keyValue}`
  if (isPending(pendingKey)) return
  setPending(pendingKey, true)
  try {
    const reservation = await reserveInventory({
      skuId: query.skuId,
      province: query.region.trim() || undefined,
      quantity: reserveQuantity.value,
      reservationKey: keyValue,
    })
    patchReservation(reservation)
    reservationKey.value = ''
    notify.success(t('inventory.reserved'), { key: 'inventory:reserve:success' })
  } catch (error) {
    notify.fromApiError(error, 'inventory.reserveFailed')
  } finally {
    setPending(pendingKey, false)
  }
}

async function releaseReservation(reservation: InventoryReservation) {
  const pendingKey = `release:${reservation.reservationKey}`
  if (isPending(pendingKey) || reservation.status !== 'RESERVED') return
  setPending(pendingKey, true)
  try {
    patchReservation(await releaseInventory(reservation.reservationKey))
    notify.success(t('inventory.released'), { key: 'inventory:release:success' })
  } catch (error) {
    notify.fromApiError(error, 'inventory.releaseFailed')
  } finally {
    setPending(pendingKey, false)
  }
}

async function runReconciliation() {
  const previous = reconciliation.value?.discrepancies ?? []
  await reconciliationState.load(async () => {
    const next = await reconcileInventory()
    return { ...next, discrepancies: mergeDiscrepancies(previous, next.discrepancies) }
  })
}

watch(
  () => query.skuId,
  () => scheduleStockLoad(),
  { immediate: true },
)

onBeforeUnmount(() => {
  if (stockLoadTimer) clearTimeout(stockLoadTimer)
})
</script>

<template>
  <div class="route-view inventory-page">
    <PageHeader
      :eyebrow="t('nav.admin')"
      :title="t('inventory.title')"
      :description="t('inventory.description')"
    />

    <AdminPageToolbar :aria-label="t('inventory.stockTable')">
      <template #search>
        <div class="field-control">
          <span>{{ t('inventory.skuId') }}</span>
          <el-input-number
            v-model="query.skuId"
            :min="1"
            controls-position="right"
            :aria-label="t('inventory.skuId')"
          />
        </div>
      </template>
      <template #filters>
        <div class="field-control">
          <span>{{ t('inventory.region') }}</span>
          <el-input
            v-model="query.region"
            clearable
            :aria-label="t('inventory.region')"
            :placeholder="t('inventory.regionPlaceholder')"
          />
        </div>
      </template>
      <template #actions>
        <el-button
          type="primary"
          :icon="Search"
          :loading="stocksState.isLoading.value"
          @click="searchStocks"
        >
          {{ t('common.search') }}
        </el-button>
        <el-button
          :icon="Refresh"
          :loading="reconciliationState.isLoading.value"
          @click="runReconciliation"
        >
          {{ t('inventory.reconcile') }}
        </el-button>
      </template>
    </AdminPageToolbar>

    <MetricStrip :items="metrics" />

    <section class="inventory-section" :aria-labelledby="'stock-table-title'">
      <h2 id="stock-table-title">{{ t('inventory.stockTable') }}</h2>
      <AsyncStateView
        :status="stocksState.status.value"
        :error="stocksState.error.value"
        :empty-title="t('inventory.emptyHint')"
        @retry="loadStocks"
      >
        <template #idle>
          <p class="section-hint">{{ t('inventory.emptyHint') }}</p>
        </template>
        <DataTableShell
          :aria-label="t('inventory.stockTable')"
          :empty="filteredStocks.length === 0"
          :busy="stocksState.status.value === 'updating'"
        >
          <template #empty>{{ t('common.noData') }}</template>
          <el-table :data="filteredStocks" row-key="warehouseId" size="small">
            <el-table-column prop="warehouseCode" :label="t('inventory.warehouse')" min-width="140" />
            <el-table-column prop="province" :label="t('inventory.region')" min-width="120" />
            <el-table-column prop="availableQuantity" :label="t('inventory.available')" width="110" />
            <el-table-column prop="lockedQuantity" :label="t('inventory.locked')" width="100" />
            <el-table-column prop="deductedQuantity" :label="t('inventory.deducted')" width="110" />
            <el-table-column prop="totalQuantity" :label="t('inventory.total')" width="100" />
            <el-table-column :label="t('inventory.safetyStock')" min-width="190">
              <template #default="{ row }">
                <span class="safety-state" :data-low="row.belowSafetyStock || undefined">
                  <el-icon aria-hidden="true">
                    <WarningFilled v-if="row.belowSafetyStock" />
                    <CircleCheck v-else />
                  </el-icon>
                  {{ row.belowSafetyStock ? t('inventory.safetyLow') : t('inventory.safetyHealthy') }}
                  · {{ row.safetyStock }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </AsyncStateView>
    </section>

    <section class="inventory-section" :aria-labelledby="'reservation-table-title'">
      <div class="section-heading">
        <h2 id="reservation-table-title">{{ t('inventory.reservations') }}</h2>
      </div>
      <div class="reservation-form">
        <div class="field-control">
          <span>{{ t('inventory.reservationKey') }}</span>
          <el-input
            v-model="reservationKey"
            :aria-label="t('inventory.reservationKey')"
            :placeholder="t('inventory.reservationKey')"
            @input="reservationError = ''"
          />
        </div>
        <div class="field-control field-control--compact">
          <span>{{ t('inventory.quantity') }}</span>
          <el-input-number
            v-model="reserveQuantity"
            :min="1"
            controls-position="right"
            :aria-label="t('inventory.quantity')"
          />
        </div>
        <el-button type="primary" :loading="isPending(`reserve:${reservationKey.trim()}`)" @click="reserveCurrentSku">
          {{ t('inventory.reserve') }}
        </el-button>
        <p v-if="reservationError" class="inline-form-error" role="alert">{{ reservationError }}</p>
      </div>
      <DataTableShell :empty="reservations.length === 0" :aria-label="t('inventory.reservations')">
        <template #empty>{{ t('inventory.noReservations') }}</template>
        <el-table :data="reservations" row-key="reservationKey" size="small">
          <el-table-column prop="reservationKey" :label="t('inventory.reservationKey')" min-width="180" />
          <el-table-column prop="quantity" :label="t('inventory.quantity')" width="100" />
          <el-table-column :label="t('common.status')" width="120">
            <template #default="{ row }">
              <el-tag :type="reservationStatusType(row.status)" effect="plain">
                {{ reservationStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="expiresAt" :label="t('inventory.expiresAt')" min-width="180" />
          <el-table-column :label="t('inventory.action')" width="130" fixed="right">
            <template #default="{ row }">
              <el-button
                size="small"
                :aria-label="t('inventory.releaseReservation', { key: row.reservationKey })"
                :disabled="row.status !== 'RESERVED'"
                :loading="isPending(`release:${row.reservationKey}`)"
                @click="releaseReservation(row)"
              >
                {{ t('inventory.release') }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </DataTableShell>
    </section>

    <section v-if="reconciliation" class="inventory-section" :aria-labelledby="'discrepancy-title'">
      <h2 id="discrepancy-title">{{ t('inventory.discrepancies') }}</h2>
      <DataTableShell :empty="discrepancies.length === 0" :aria-label="t('inventory.discrepancies')">
        <template #empty>{{ t('inventory.noDiscrepancies') }}</template>
        <el-table :data="discrepancies" row-key="warehouseId" size="small">
          <el-table-column prop="skuId" label="SKU" width="100" />
          <el-table-column prop="warehouseId" :label="t('inventory.warehouse')" width="120" />
          <el-table-column prop="actualLocked" :label="t('inventory.actualLocked')" width="120" />
          <el-table-column prop="expectedLocked" :label="t('inventory.expectedLocked')" width="130" />
          <el-table-column prop="actualDeducted" :label="t('inventory.actualDeducted')" width="130" />
          <el-table-column prop="expectedDeducted" :label="t('inventory.expectedDeducted')" width="140" />
        </el-table>
      </DataTableShell>
    </section>
  </div>
</template>

<style scoped>
.inventory-page,
.inventory-section {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
}

.inventory-page {
  gap: var(--space-5);
}

.inventory-section h2,
.section-hint,
.inline-form-error {
  margin: 0;
}

.inventory-section h2 {
  font-size: var(--text-lg);
}

.field-control {
  display: grid;
  gap: var(--space-1);
  min-width: min(220px, 100%);
}

.field-control span {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.field-control--compact {
  min-width: 140px;
}

.reservation-form {
  display: flex;
  align-items: end;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.inline-form-error {
  flex-basis: 100%;
  color: var(--color-danger);
  font-size: var(--text-sm);
}

.section-hint {
  padding: var(--space-8) 0;
  color: var(--color-text-muted);
  text-align: center;
}

.safety-state {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  color: var(--color-success);
}

.safety-state[data-low='true'] {
  color: var(--color-warning);
}

@media (max-width: 600px) {
  .reservation-form,
  .reservation-form :deep(.el-button),
  .field-control,
  .field-control :deep(.el-input),
  .field-control :deep(.el-input-number) {
    width: 100%;
    max-width: none;
  }
}
</style>

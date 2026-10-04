<script setup lang="ts">
import { CircleCheck, Refresh, Search, WarningFilled } from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import {
  inventoryStocks,
  reconcileInventory,
  releaseInventory,
  reserveInventory,
} from '@/api/inventory'
import { parsePositiveApiId, sameApiId, type ApiId } from '@/api/ids'
import AdminPageToolbar from '@/components/admin/AdminPageToolbar.vue'
import MetricStrip, { type MetricItem } from '@/components/admin/MetricStrip.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
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
  skuId: ApiId | null
  region: string
}

type ReservationStatus = InventoryReservation['status']

// RouterView may recreate this view while a query-only navigation settles. Keep the
// session-local snapshots above the component scope so an in-flight mutation can still
// reconcile into the active view without losing the previous table identities.
const inventorySessionStocksState = useAsyncState<WarehouseStock[]>({ preserveData: true })
const inventorySessionReconciliationState = useAsyncState<InventoryReconciliation>({
  preserveData: true,
})
const inventorySessionReservations = ref<InventoryReservation[]>([])
const inventorySessionPendingKeys = ref(new Set<string>())
const inventorySessionActiveSkuId = ref<ApiId | null>(null)
const inventorySessionAppliedSkuId = ref<ApiId | null>(null)
const inventorySessionAppliedRegion = ref('')
const inventorySessionStockQueryPending = ref(false)

const inventoryQuerySchema: RouteQuerySchema<InventoryQuery> = {
  parse(query: LocationQuery) {
    const rawSku = Array.isArray(query.skuId) ? query.skuId[0] : query.skuId
    const parsedSku = parsePositiveApiId(rawSku)
    const rawRegion = Array.isArray(query.region) ? query.region[0] : query.region
    return {
      skuId: parsedSku ?? null,
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
const stocksState = inventorySessionStocksState
const reconciliationState = inventorySessionReconciliationState
const reservations = inventorySessionReservations
const reservationKey = ref('')
const reserveQuantity = ref(1)
const reservationError = ref('')
const pendingKeys = inventorySessionPendingKeys
const stockQueryPending = inventorySessionStockQueryPending
let stockLoadTimer: ReturnType<typeof setTimeout> | undefined

const stocks = computed(() => stocksState.data.value ?? [])
const appliedRegion = inventorySessionAppliedRegion
const appliedSkuId = inventorySessionAppliedSkuId
const activeSkuId = inventorySessionActiveSkuId
const normalizedRegion = computed(() => query.region.trim().toLocaleLowerCase())
const displayedRegion = computed(() =>
  query.skuId !== appliedSkuId.value || stocksState.status.value === 'updating'
    ? appliedRegion.value
    : normalizedRegion.value,
)
const filteredStocks = computed(() => {
  if (!displayedRegion.value) return stocks.value
  return stocks.value.filter((stock) => {
    const haystack = `${stock.province ?? ''} ${stock.warehouseCode ?? ''}`.toLocaleLowerCase()
    return haystack.includes(displayedRegion.value)
  })
})
const stockViewStatus = computed(() => {
  if (stockQueryPending.value && stocksState.data.value !== null) return 'updating' as const
  return stocksState.status.value
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

function reservationStatusTone(status: ReservationStatus): 'success' | 'warning' | 'info' {
  if (status === 'RESERVED') return 'warning'
  if (status === 'DEDUCTED') return 'success'
  return 'info'
}

function stockRowKey(stock: WarehouseStock): string {
  return `stock:${stock.skuId}:${stock.warehouseId}`
}

function reservationRowKey(reservation: InventoryReservation): string {
  return `reservation:${reservation.reservationKey}`
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

function canPatchStockForSku(skuId: ApiId): boolean {
  return sameApiId(activeSkuId.value, skuId) && sameApiId(appliedSkuId.value, skuId)
}

function patchStock(nextStock: WarehouseStock) {
  const rows = stocksState.data.value
  if (!rows || !canPatchStockForSku(nextStock.skuId)) return
  const index = rows.findIndex(
    (stock) =>
      sameApiId(stock.skuId, nextStock.skuId) &&
      sameApiId(stock.warehouseId, nextStock.warehouseId),
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
  if (canPatchStockForSku(nextReservation.skuId)) patchStock(nextReservation.stock)
}

function discrepancyKey(row: InventoryDiscrepancy): string {
  return `${row.skuId}:${row.warehouseId}`
}

function mergeDiscrepancies(
  previous: InventoryDiscrepancy[],
  next: InventoryDiscrepancy[],
): InventoryDiscrepancy[] {
  const nextKeys = new Set(next.map(discrepancyKey))
  const merged = previous.filter((row) => nextKeys.has(discrepancyKey(row)))
  for (const row of next) {
    const index = merged.findIndex((candidate) => discrepancyKey(candidate) === discrepancyKey(row))
    if (index >= 0) merged.splice(index, 1, row)
    else merged.push(row)
  }
  return merged
}

async function loadStocks() {
  console.error(
    'DEBUG shared load',
    query.skuId,
    activeSkuId.value,
    appliedSkuId.value,
    stocksState.data.value,
    stocksState.status.value,
  )
  if (activeSkuId.value !== query.skuId) return
  if (!query.skuId) {
    appliedRegion.value = ''
    appliedSkuId.value = null
    stockQueryPending.value = false
    stocksState.reset()
    return
  }
  const skuId = query.skuId
  const requestedRegion = normalizedRegion.value
  const loaded = await stocksState.load(() => inventoryStocks(skuId), {
    preserveData: true,
    isEmpty: (rows) => rows.length === 0,
  })
  if (loaded !== null && activeSkuId.value === skuId) {
    appliedSkuId.value = skuId
    appliedRegion.value = requestedRegion
    stockQueryPending.value = false
  } else if (activeSkuId.value === skuId && stocksState.status.value === 'error') {
    stockQueryPending.value = false
  }
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
  const skuId = query.skuId
  reservationError.value = ''
  if (!skuId || !keyValue) {
    reservationError.value = t('inventory.reservationKeyRequired')
    return
  }
  const pendingKey = `reserve:${keyValue}`
  if (isPending(pendingKey)) return
  setPending(pendingKey, true)
  try {
    const reservation = await reserveInventory({
      skuId,
      province: query.region.trim() || undefined,
      quantity: reserveQuantity.value,
      reservationKey: keyValue,
    })
    if (canPatchStockForSku(reservation.skuId)) stocksState.cancel()
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
    const releasedReservation = await releaseInventory(reservation.reservationKey)
    if (canPatchStockForSku(releasedReservation.skuId)) stocksState.cancel()
    patchReservation(releasedReservation)
    notify.success(t('inventory.released'), { key: 'inventory:release:success' })
  } catch (error) {
    notify.fromApiError(error, 'inventory.releaseFailed')
  } finally {
    setPending(pendingKey, false)
  }
}

async function runReconciliation() {
  if (reconciliationState.isLoading.value) return
  const previous = reconciliation.value?.discrepancies ?? []
  await reconciliationState.load(
    async () => {
      const next = await reconcileInventory()
      return { ...next, discrepancies: mergeDiscrepancies(previous, next.discrepancies) }
    },
    { isEmpty: (result) => result.discrepancies.length === 0 },
  )
}

watch(
  () => query.skuId,
  () => {
    activeSkuId.value = query.skuId
    stockQueryPending.value = query.skuId !== null && query.skuId !== appliedSkuId.value
    scheduleStockLoad()
  },
  { immediate: true },
)

onMounted(() => {
  void replaceNow().catch(() => undefined)
})

onBeforeUnmount(() => {
  if (stockLoadTimer) clearTimeout(stockLoadTimer)
})
</script>

<template>
  <div
    class="route-view inventory-page commerce-page"
    data-surface="inventory-observatory"
    data-layout="stock-reservation-reconciliation"
  >
    <PageHeader
      :eyebrow="t('nav.admin')"
      :title="t('inventory.title')"
      :description="t('inventory.description')"
    />

    <AdminPageToolbar :aria-label="t('inventory.stockTable')">
      <template #search>
        <div class="field-control">
          <span>{{ t('inventory.skuId') }}</span>
          <el-input
            v-model="query.skuId"
            :aria-label="t('inventory.skuId')"
            :placeholder="t('inventory.skuId')"
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
          :disabled="reconciliationState.isLoading.value"
          @click="runReconciliation"
        >
          {{ t('inventory.reconcile') }}
        </el-button>
      </template>
    </AdminPageToolbar>

    <MetricStrip :items="metrics" />

    <section
      class="inventory-section commerce-section"
      data-workspace="stock"
      :aria-labelledby="'stock-table-title'"
    >
      <h2 id="stock-table-title">{{ t('inventory.stockTable') }}</h2>
      <AsyncStateView
        :status="stockViewStatus"
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
          :busy="stockViewStatus === 'updating'"
        >
          <template #empty>{{ t('common.noData') }}</template>
          <el-table
            class="inventory-data-table"
            :data="filteredStocks"
            :row-key="stockRowKey"
            row-class-name="inventory-data-row"
            size="small"
          >
            <el-table-column :label="t('inventory.warehouse')" min-width="140">
              <template #default="{ row }">
                <span class="table-row-anchor" :data-row-key="stockRowKey(row)">
                  {{ row.warehouseCode }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="province" :label="t('inventory.region')" min-width="120" />
            <el-table-column
              prop="availableQuantity"
              :label="t('inventory.available')"
              width="110"
            />
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
                  {{
                    row.belowSafetyStock ? t('inventory.safetyLow') : t('inventory.safetyHealthy')
                  }}
                  {{ t('inventory.safetyThreshold', { value: row.safetyStock }) }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </AsyncStateView>
    </section>

    <section
      class="inventory-section commerce-section"
      data-workspace="reservations"
      :aria-labelledby="'reservation-table-title'"
    >
      <div class="section-heading commerce-section__heading">
        <h2 id="reservation-table-title">{{ t('inventory.reservations') }}</h2>
      </div>
      <div class="reservation-form commerce-form-grid" data-surface="reservation-form">
        <div class="field-control commerce-field">
          <span>{{ t('inventory.reservationKey') }}</span>
          <el-input
            v-model="reservationKey"
            :aria-label="t('inventory.reservationKey')"
            :placeholder="t('inventory.reservationKey')"
            @input="reservationError = ''"
          />
        </div>
        <div class="field-control field-control--compact commerce-field">
          <span>{{ t('inventory.quantity') }}</span>
          <el-input-number
            v-model="reserveQuantity"
            :min="1"
            controls-position="right"
            :aria-label="t('inventory.quantity')"
          />
        </div>
        <div class="commerce-actions">
          <el-button
            type="primary"
            :loading="isPending(`reserve:${reservationKey.trim()}`)"
            @click="reserveCurrentSku"
          >
            {{ t('inventory.reserve') }}
          </el-button>
        </div>
        <p v-if="reservationError" class="inline-form-error" role="alert">{{ reservationError }}</p>
      </div>
      <DataTableShell :empty="reservations.length === 0" :aria-label="t('inventory.reservations')">
        <template #empty>{{ t('inventory.noReservations') }}</template>
        <el-table
          class="inventory-data-table"
          :data="reservations"
          :row-key="reservationRowKey"
          row-class-name="inventory-data-row"
          size="small"
        >
          <el-table-column :label="t('inventory.reservationKey')" min-width="180">
            <template #default="{ row }">
              <span class="table-row-anchor" :data-row-key="reservationRowKey(row)">
                {{ row.reservationKey }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="quantity" :label="t('inventory.quantity')" width="100" />
          <el-table-column :label="t('common.status')" width="120">
            <template #default="{ row }">
              <StatusTag
                :status="row.status"
                :label="reservationStatusLabel(row.status)"
                :tone="reservationStatusTone(row.status)"
              />
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

    <section
      class="inventory-section commerce-section"
      data-workspace="reconciliation"
      :aria-labelledby="'discrepancy-title'"
    >
      <h2 id="discrepancy-title">{{ t('inventory.discrepancies') }}</h2>
      <AsyncStateView
        :status="reconciliationState.status.value"
        :error="reconciliationState.error.value"
        :empty-title="t('inventory.noDiscrepancies')"
        @retry="runReconciliation"
      >
        <template #idle>
          <p class="section-hint">{{ t('inventory.reconciliationHint') }}</p>
        </template>
        <DataTableShell
          :empty="discrepancies.length === 0"
          :aria-label="t('inventory.discrepancies')"
        >
          <template #empty>{{ t('inventory.noDiscrepancies') }}</template>
          <el-table
            class="inventory-data-table"
            :data="discrepancies"
            :row-key="discrepancyKey"
            row-class-name="inventory-data-row"
            size="small"
          >
            <el-table-column :label="t('inventory.skuId')" width="100">
              <template #default="{ row }">
                <span
                  class="table-row-anchor"
                  :data-row-key="`discrepancy:${row.skuId}:${row.warehouseId}`"
                >
                  {{ row.skuId }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="warehouseId" :label="t('inventory.warehouse')" width="120" />
            <el-table-column prop="actualLocked" :label="t('inventory.actualLocked')" width="120" />
            <el-table-column
              prop="expectedLocked"
              :label="t('inventory.expectedLocked')"
              width="130"
            />
            <el-table-column
              prop="actualDeducted"
              :label="t('inventory.actualDeducted')"
              width="130"
            />
            <el-table-column
              prop="expectedDeducted"
              :label="t('inventory.expectedDeducted')"
              width="140"
            />
          </el-table>
        </DataTableShell>
      </AsyncStateView>
    </section>
  </div>
</template>

<style scoped>
.inventory-page {
  display: grid;
  gap: var(--space-5);
  width: 100%;
  min-width: 0;
  max-width: 100%;
  container-type: inline-size;
}

.inventory-section {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
  padding-block: var(--space-5);
  border-block: 1px solid var(--admin-line);
  background: transparent;
}

.inventory-section + .inventory-section {
  border-top: 0;
}

.inventory-section h2,
.section-hint,
.inline-form-error {
  margin: 0;
}

.inventory-section h2 {
  color: var(--admin-ink);
  font-size: var(--text-lg);
  line-height: var(--leading-tight);
}

.field-control {
  display: grid;
  gap: var(--space-2);
  min-width: min(220px, 100%);
}

.field-control span {
  color: var(--admin-ink);
  font-size: var(--text-sm);
  font-weight: var(--font-weight-semibold);
}

.field-control--compact {
  min-width: 140px;
}

.reservation-form {
  grid-template-columns: minmax(180px, 1fr) minmax(140px, 220px) auto;
  align-items: end;
  gap: var(--space-3);
}

.inline-form-error {
  grid-column: 1 / -1;
  color: var(--admin-danger);
  font-size: var(--text-sm);
}

.section-hint {
  padding: var(--space-8) 0;
  color: var(--admin-muted);
  text-align: center;
}

.safety-state {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  color: var(--admin-success);
  font-weight: var(--font-weight-semibold);
}

.safety-state[data-low='true'] {
  color: var(--admin-warning);
}

.inventory-data-table {
  min-width: 760px;
  color: var(--admin-ink);
  font-variant-numeric: tabular-nums;
}

.inventory-data-table :deep(.el-table__header-wrapper th.el-table__cell) {
  background: var(--admin-surface-subtle);
  color: var(--admin-ink);
  font-size: var(--text-xs);
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.02em;
}

.inventory-data-table :deep(.el-table__body tr) {
  transition: background-color var(--motion-fast);
}

.inventory-data-table :deep(.el-table__body tr:hover > td.el-table__cell),
.inventory-data-table :deep(.el-table__body tr:focus-within > td.el-table__cell) {
  background: var(--admin-primary-soft);
}

.inventory-data-table :deep(.el-table) {
  width: max-content;
  min-width: 100%;
}

.table-row-anchor {
  display: inline-flex;
  min-width: 0;
  color: var(--admin-ink);
  font-weight: var(--font-weight-semibold);
  overflow-wrap: anywhere;
}

@media (max-width: 760px) {
  .inventory-page :deep(.admin-page-toolbar > *) {
    flex: 0 1 auto;
    width: 100%;
    max-width: none;
  }

  .inventory-page :deep(.admin-page-toolbar__search),
  .inventory-page :deep(.admin-page-toolbar__filters),
  .inventory-page :deep(.admin-page-toolbar__actions) {
    width: 100%;
    max-width: none;
  }

  .reservation-form {
    grid-template-columns: 1fr;
  }

  .reservation-form :deep(.el-button),
  .field-control :deep(.el-input),
  .field-control :deep(.el-input-number),
  .reservation-form .commerce-actions {
    width: 100%;
    max-width: none;
  }

  .reservation-form :deep(button),
  .reservation-form :deep(input),
  .reservation-form :deep(.el-input__wrapper),
  .reservation-form :deep(.el-input-number),
  .reservation-form :deep(.el-button) {
    min-height: var(--touch-target-min);
  }
}
</style>

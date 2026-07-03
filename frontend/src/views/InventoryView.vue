<script setup lang="ts">
import { Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, ref } from 'vue'
import {
  inventoryStocks,
  reconcileInventory,
  reserveInventory,
  releaseInventory,
} from '@/api/inventory'
import AppShell from '@/components/AppShell.vue'
import type { InventoryReconciliation, InventoryReservation, WarehouseStock } from '@/types'

const skuId = ref<number | null>(null)
const reservationKey = ref('')
const reserveQuantity = ref(1)
const reserveProvince = ref('')
const loadingStocks = ref(false)
const reconciling = ref(false)
const movingStock = ref(false)
const stocks = ref<WarehouseStock[]>([])
const latestReservation = ref<InventoryReservation | null>(null)
const reconciliation = ref<InventoryReconciliation | null>(null)

const totalAvailable = computed(() =>
  stocks.value.reduce((sum, stock) => sum + stock.availableQuantity, 0),
)
const totalLocked = computed(() =>
  stocks.value.reduce((sum, stock) => sum + stock.lockedQuantity, 0),
)

async function loadStocks() {
  if (!skuId.value) {
    return
  }
  loadingStocks.value = true
  try {
    stocks.value = await inventoryStocks(skuId.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load inventory')
  } finally {
    loadingStocks.value = false
  }
}

async function reserveCurrentSku() {
  if (!skuId.value || !reservationKey.value.trim()) {
    return
  }
  movingStock.value = true
  try {
    latestReservation.value = await reserveInventory({
      skuId: skuId.value,
      province: reserveProvince.value || undefined,
      quantity: reserveQuantity.value,
      reservationKey: reservationKey.value,
    })
    await loadStocks()
    ElMessage.success('Inventory reserved')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to reserve inventory')
  } finally {
    movingStock.value = false
  }
}

async function releaseLatestReservation() {
  if (!latestReservation.value) {
    return
  }
  movingStock.value = true
  try {
    latestReservation.value = await releaseInventory(latestReservation.value.reservationKey)
    await loadStocks()
    ElMessage.success('Inventory released')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to release inventory')
  } finally {
    movingStock.value = false
  }
}

async function runReconciliation() {
  reconciling.value = true
  try {
    reconciliation.value = await reconcileInventory()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to reconcile inventory')
  } finally {
    reconciling.value = false
  }
}
</script>

<template>
  <AppShell>
    <section class="inventory-layout">
      <div class="inventory-toolbar">
        <el-input-number v-model="skuId" :min="1" controls-position="right" placeholder="SKU ID" />
        <el-button type="primary" :icon="Search" :loading="loadingStocks" @click="loadStocks">
          Search
        </el-button>
        <el-button :icon="Refresh" :loading="reconciling" @click="runReconciliation">
          Reconcile
        </el-button>
      </div>

      <div class="inventory-summary">
        <div>
          <span>Available</span>
          <strong>{{ totalAvailable }}</strong>
        </div>
        <div>
          <span>Locked</span>
          <strong>{{ totalLocked }}</strong>
        </div>
        <div>
          <span>Reconciliation</span>
          <strong>{{ reconciliation?.balanced === false ? 'Drift' : 'Balanced' }}</strong>
        </div>
      </div>

      <el-table v-loading="loadingStocks" :data="stocks" class="inventory-table">
        <el-table-column prop="warehouseCode" label="Warehouse" min-width="140" />
        <el-table-column prop="province" label="Region" min-width="120" />
        <el-table-column prop="availableQuantity" label="Available" min-width="110" />
        <el-table-column prop="lockedQuantity" label="Locked" min-width="100" />
        <el-table-column prop="deductedQuantity" label="Deducted" min-width="110" />
        <el-table-column prop="totalQuantity" label="Total" min-width="100" />
        <el-table-column label="Safety" min-width="100">
          <template #default="{ row }">
            <el-tag :type="row.belowSafetyStock ? 'warning' : 'success'" disable-transitions>
              {{ row.safetyStock }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="inventory-actions">
        <el-input v-model="reservationKey" placeholder="Reservation key" />
        <el-input v-model="reserveProvince" placeholder="Region, e.g. CN-BJ" />
        <el-input-number v-model="reserveQuantity" :min="1" controls-position="right" />
        <el-button type="primary" :loading="movingStock" @click="reserveCurrentSku">
          Reserve
        </el-button>
        <el-button
          :disabled="!latestReservation"
          :loading="movingStock"
          @click="releaseLatestReservation"
        >
          Release
        </el-button>
      </div>

      <el-alert
        v-if="latestReservation"
        type="success"
        :closable="false"
        :title="`${latestReservation.status} ${latestReservation.reservationKey}`"
      />

      <el-table
        v-if="reconciliation && !reconciliation.balanced"
        :data="reconciliation.discrepancies"
        class="inventory-table"
      >
        <el-table-column prop="skuId" label="SKU" min-width="100" />
        <el-table-column prop="warehouseId" label="Warehouse" min-width="120" />
        <el-table-column prop="actualLocked" label="Locked" min-width="100" />
        <el-table-column prop="expectedLocked" label="Expected locked" min-width="150" />
        <el-table-column prop="actualDeducted" label="Deducted" min-width="110" />
        <el-table-column prop="expectedDeducted" label="Expected deducted" min-width="170" />
      </el-table>
    </section>
  </AppShell>
</template>

<style scoped>
.inventory-layout {
  display: grid;
  gap: 16px;
}

.inventory-toolbar,
.inventory-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.inventory-toolbar .el-input-number,
.inventory-actions .el-input,
.inventory-actions .el-input-number {
  max-width: 220px;
}

.inventory-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.inventory-summary > div {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 14px;
}

.inventory-summary span {
  color: var(--el-text-color-secondary);
  display: block;
  font-size: 0.85rem;
}

.inventory-summary strong {
  display: block;
  font-size: 1.35rem;
  margin-top: 4px;
}

.inventory-table {
  width: 100%;
}
</style>

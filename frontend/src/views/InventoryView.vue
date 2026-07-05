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

type ReservationStatus = InventoryReservation['status']

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

const reservationStatusLabels: Record<ReservationStatus, string> = {
  RESERVED: '已锁定',
  RELEASED: '已释放',
  DEDUCTED: '已扣减',
  EXPIRED: '已过期',
}

function reservationStatusLabel(status: ReservationStatus): string {
  return reservationStatusLabels[status] ?? status
}

async function loadStocks() {
  if (!skuId.value) {
    return
  }
  loadingStocks.value = true
  try {
    stocks.value = await inventoryStocks(skuId.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '库存加载失败')
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
    ElMessage.success('库存已锁定')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '库存锁定失败')
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
    ElMessage.success('库存已释放')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '库存释放失败')
  } finally {
    movingStock.value = false
  }
}

async function runReconciliation() {
  reconciling.value = true
  try {
    reconciliation.value = await reconcileInventory()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '库存对账失败')
  } finally {
    reconciling.value = false
  }
}
</script>

<template>
  <AppShell>
    <section class="inventory-layout">
      <div class="inventory-toolbar">
        <el-input-number
          v-model="skuId"
          :min="1"
          controls-position="right"
          placeholder="SKU 编号"
        />
        <el-button type="primary" :icon="Search" :loading="loadingStocks" @click="loadStocks">
          查询
        </el-button>
        <el-button :icon="Refresh" :loading="reconciling" @click="runReconciliation">
          对账
        </el-button>
      </div>

      <div class="inventory-summary">
        <div>
          <span>可售库存</span>
          <strong>{{ totalAvailable }}</strong>
        </div>
        <div>
          <span>锁定库存</span>
          <strong>{{ totalLocked }}</strong>
        </div>
        <div>
          <span>对账状态</span>
          <strong>{{ reconciliation?.balanced === false ? '存在差异' : '平衡' }}</strong>
        </div>
      </div>

      <el-table v-loading="loadingStocks" :data="stocks" class="inventory-table">
        <el-table-column prop="warehouseCode" label="仓库" min-width="140" />
        <el-table-column prop="province" label="区域" min-width="120" />
        <el-table-column prop="availableQuantity" label="可售" min-width="110" />
        <el-table-column prop="lockedQuantity" label="锁定" min-width="100" />
        <el-table-column prop="deductedQuantity" label="已扣减" min-width="110" />
        <el-table-column prop="totalQuantity" label="总库存" min-width="100" />
        <el-table-column label="安全线" min-width="100">
          <template #default="{ row }">
            <el-tag :type="row.belowSafetyStock ? 'warning' : 'success'" disable-transitions>
              {{ row.safetyStock }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="inventory-actions">
        <el-input v-model="reservationKey" placeholder="锁定单号" />
        <el-input v-model="reserveProvince" placeholder="区域，例如 CN-BJ" />
        <el-input-number v-model="reserveQuantity" :min="1" controls-position="right" />
        <el-button type="primary" :loading="movingStock" @click="reserveCurrentSku">
          锁定库存
        </el-button>
        <el-button
          :disabled="!latestReservation"
          :loading="movingStock"
          @click="releaseLatestReservation"
        >
          释放库存
        </el-button>
      </div>

      <el-alert
        v-if="latestReservation"
        type="success"
        :closable="false"
        :title="`锁定单 ${latestReservation.reservationKey}：${reservationStatusLabel(latestReservation.status)}`"
      />

      <el-table
        v-if="reconciliation && !reconciliation.balanced"
        :data="reconciliation.discrepancies"
        class="inventory-table"
      >
        <el-table-column prop="skuId" label="SKU" min-width="100" />
        <el-table-column prop="warehouseId" label="仓库" min-width="120" />
        <el-table-column prop="actualLocked" label="实际锁定" min-width="110" />
        <el-table-column prop="expectedLocked" label="应锁定" min-width="120" />
        <el-table-column prop="actualDeducted" label="实际扣减" min-width="110" />
        <el-table-column prop="expectedDeducted" label="应扣减" min-width="120" />
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

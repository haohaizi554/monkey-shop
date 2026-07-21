<script setup lang="ts">
import { CreditCard, RefreshLeft, RefreshRight, Search, Van } from '@element-plus/icons-vue'
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import type { ApiId } from '@/api/ids'
import AdminCommerceNav from '@/components/admin/AdminCommerceNav.vue'
import AdminPageToolbar from '@/components/admin/AdminPageToolbar.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { useAdminOrders } from '@/composables/useAdminOrders'
import { dateTime, money } from '@/utils/format'
import { hasAdminOrderAction, normalizeAdminOrderStatus } from '@/utils/adminOrderActions'

defineOptions({ name: 'OrderOperationsView' })

const { t } = useI18n()
const router = useRouter()
const keyword = ref('')
const statusFilter = ref('')
let filterTimer: ReturnType<typeof setTimeout> | null = null

const availableStatuses = [
  'PENDING_PAYMENT',
  'PAID',
  'PARTIALLY_SHIPPED',
  'SHIPPED',
  'PARTIALLY_RECEIVED',
  'COMPLETED',
  'RETURN_REQUESTED',
  'WAITING_RETURN_SHIPMENT',
  'RETURN_SHIPPING',
  'REFUNDED',
] as const

const { orders, page, pageSize, currentPage, status, error, loadOrders, changePage } =
  useAdminOrders({
    statuses: () => (statusFilter.value ? [statusFilter.value] : undefined),
    keyword: () => keyword.value,
  })

const viewStatus = computed(() => status.value)

function openWorkspace(path: string, orderId: ApiId) {
  void router.push({ path, query: { orderId: String(orderId) } })
}

watch([keyword, statusFilter], () => {
  if (filterTimer !== null) clearTimeout(filterTimer)
  filterTimer = setTimeout(() => {
    filterTimer = null
    void loadOrders(0)
  }, 250)
})

onUnmounted(() => {
  if (filterTimer !== null) clearTimeout(filterTimer)
})
</script>

<template>
  <div class="route-view commerce-page">
    <PageHeader
      :eyebrow="t('adminCommerce.workspace')"
      :title="t('adminCommerce.ordersTitle')"
      :description="t('adminCommerce.ordersDescription')"
    >
      <template #actions>
        <el-button :icon="RefreshRight" :loading="status === 'updating'" @click="loadOrders()">
          {{ t('adminCommerce.refreshOrders') }}
        </el-button>
      </template>
    </PageHeader>

    <AdminCommerceNav />

    <AdminPageToolbar>
      <template #search>
        <el-input
          v-model="keyword"
          clearable
          :prefix-icon="Search"
          :placeholder="t('adminCommerce.searchOrders')"
          :aria-label="t('adminCommerce.searchOrders')"
        />
      </template>
      <template #filters>
        <el-select
          v-model="statusFilter"
          clearable
          :placeholder="t('adminCommerce.allStatuses')"
          :aria-label="t('adminCommerce.statusFilter')"
        >
          <el-option
            v-for="option in availableStatuses"
            :key="option"
            :value="option"
            :label="option.replaceAll('_', ' ')"
          />
        </el-select>
      </template>
    </AdminPageToolbar>

    <AsyncStateView
      :status="viewStatus"
      mode="table"
      :error="error"
      :empty-title="t('adminCommerce.noOrders')"
      preserve-content-on-error
      @retry="loadOrders()"
    >
      <DataTableShell :aria-label="t('adminCommerce.ordersTitle')" :busy="status === 'updating'">
        <table class="commerce-table">
          <thead>
            <tr>
              <th scope="col">{{ t('adminCommerce.orderNo') }}</th>
              <th scope="col">{{ t('adminCommerce.buyer') }}</th>
              <th scope="col">{{ t('adminCommerce.product') }}</th>
              <th scope="col">{{ t('adminCommerce.amount') }}</th>
              <th scope="col">{{ t('common.status') }}</th>
              <th scope="col">{{ t('adminCommerce.createdAt') }}</th>
              <th scope="col">{{ t('adminCommerce.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="order in orders" :key="order.id">
              <td>
                <span class="commerce-table__primary">
                  <strong>{{ order.orderNo }}</strong>
                  <small>#{{ order.id }}</small>
                </span>
              </td>
              <td>{{ order.buyerName }}</td>
              <td>{{ order.productName }}</td>
              <td>{{ money(order.price) }}</td>
              <td><StatusTag :status="normalizeAdminOrderStatus(order.status)" /></td>
              <td>{{ dateTime(order.createTime) }}</td>
              <td>
                <div class="commerce-actions">
                  <el-button
                    v-if="hasAdminOrderAction(order.status, 'viewPayment')"
                    link
                    type="primary"
                    :icon="CreditCard"
                    @click="openWorkspace('/admin/payments', order.id)"
                  >
                    {{ t('adminCommerce.openPayment') }}
                  </el-button>
                  <el-button
                    v-if="
                      hasAdminOrderAction(order.status, 'approveReturn') ||
                      hasAdminOrderAction(order.status, 'refundReturn')
                    "
                    link
                    type="warning"
                    :icon="RefreshLeft"
                    @click="openWorkspace('/admin/returns', order.id)"
                  >
                    {{ t('adminCommerce.openReturns') }}
                  </el-button>
                  <el-button
                    v-if="
                      hasAdminOrderAction(order.status, 'ship') ||
                      hasAdminOrderAction(order.status, 'viewShipments')
                    "
                    link
                    type="success"
                    :icon="Van"
                    @click="openWorkspace('/admin/logistics', order.id)"
                  >
                    {{ t('adminCommerce.openLogistics') }}
                  </el-button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </DataTableShell>
      <el-pagination
        v-if="(page?.totalElements ?? 0) > pageSize"
        class="admin-orders-pagination"
        background
        layout="prev, pager, next, total"
        :current-page="currentPage + 1"
        :page-size="pageSize"
        :total="page?.totalElements ?? 0"
        @current-change="changePage"
      />
    </AsyncStateView>
  </div>
</template>

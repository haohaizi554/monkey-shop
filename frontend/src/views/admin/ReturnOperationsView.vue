<script setup lang="ts">
import { Check, RefreshRight, Search, Wallet } from '@element-plus/icons-vue'
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import * as ordersApi from '@/api/orders'
import { adminPaymentForOrder, adminRefundPayment } from '@/api/payments'
import AdminCommerceNav from '@/components/admin/AdminCommerceNav.vue'
import AdminPageToolbar from '@/components/admin/AdminPageToolbar.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { useAdminOrders } from '@/composables/useAdminOrders'
import { useNotify } from '@/composables/useNotify'
import type { Order } from '@/types'
import { hasAdminOrderAction, normalizeAdminOrderStatus } from '@/utils/adminOrderActions'
import { dateTime, money } from '@/utils/format'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'

defineOptions({ name: 'ReturnOperationsView' })

const { t } = useI18n()
const notify = useNotify()
const keyword = ref('')
const pendingOrderIds = ref(new Set<number>())
const returnStatuses = ['RETURN_REQUESTED', 'WAITING_RETURN_SHIPMENT', 'RETURN_SHIPPING'] as const
let filterTimer: ReturnType<typeof setTimeout> | null = null

const { orders, page, pageSize, currentPage, status, error, loadOrders, changePage, patchOrder } =
  useAdminOrders({
    statuses: () => returnStatuses,
    keyword: () => keyword.value,
  })

const returnOrders = computed(() => orders.value)

const viewStatus = computed(() => {
  if ((status.value === 'success' || status.value === 'empty') && returnOrders.value.length === 0) {
    return 'empty'
  }
  return status.value
})

watch(keyword, () => {
  if (filterTimer !== null) clearTimeout(filterTimer)
  filterTimer = setTimeout(() => {
    filterTimer = null
    void loadOrders(0)
  }, 250)
})

onUnmounted(() => {
  if (filterTimer !== null) clearTimeout(filterTimer)
})

function isPending(orderId: number) {
  return pendingOrderIds.value.has(orderId)
}

function setPending(orderId: number, pending: boolean) {
  const next = new Set(pendingOrderIds.value)
  if (pending) {
    next.add(orderId)
  } else {
    next.delete(orderId)
  }
  pendingOrderIds.value = next
}

async function approveReturn(order: Order) {
  if (!hasAdminOrderAction(order.status, 'approveReturn') || isPending(order.id)) {
    return
  }
  const accepted = await notify.confirm({
    content: t('adminCommerce.approveReturnConfirm', { orderNo: order.orderNo }),
    confirmText: t('common.ok'),
  })
  if (!accepted) {
    return
  }

  setPending(order.id, true)
  try {
    patchOrder(await ordersApi.approveReturn(order.id))
    notify.success(t('adminCommerce.orderUpdated'), { key: `return:approve:${order.id}` })
  } catch (caught) {
    notify.fromApiError(caught, 'common.unableToUpdateOrder')
  } finally {
    setPending(order.id, false)
  }
}

async function refundReturn(order: Order) {
  if (!hasAdminOrderAction(order.status, 'refundReturn') || isPending(order.id)) {
    return
  }
  const accepted = await notify.confirm({
    content: t('adminCommerce.refundConfirm', { orderNo: order.orderNo }),
    confirmText: t('adminCommerce.refund'),
    type: 'warning',
  })
  if (!accepted) {
    return
  }

  setPending(order.id, true)
  let refundCompleted = false
  try {
    const payment = await adminPaymentForOrder(order.id)
    const outstanding = Math.max(
      0,
      Number(payment.paidAmount || payment.amount) - Number(payment.refundedAmount || 0),
    )
    if (payment.status !== 'REFUNDED' && outstanding > 0) {
      const payload = {
        paymentNo: payment.paymentNo,
        amount: outstanding.toFixed(2),
        reason: t('adminCommerce.refund'),
      }
      const intent = getIdempotencyIntent('admin:return-refund', payload)
      await adminRefundPayment(payload, intent.key)
      intent.complete()
    }
    refundCompleted = true
    patchOrder(await ordersApi.confirmReturn(order.id))
    notify.success(t('adminCommerce.refundComplete'), { key: `return:refund:${order.id}` })
  } catch (caught) {
    if (refundCompleted) {
      notify.warning(t('admin.refundCompleteReturnPending'), {
        key: `return:confirm-pending:${order.id}`,
      })
    } else {
      notify.fromApiError(caught, 'common.unableToUpdateOrder')
    }
  } finally {
    setPending(order.id, false)
  }
}
</script>

<template>
  <div class="route-view commerce-page">
    <PageHeader
      :eyebrow="t('adminCommerce.workspace')"
      :title="t('adminCommerce.returnsTitle')"
      :description="t('adminCommerce.returnsDescription')"
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
    </AdminPageToolbar>

    <AsyncStateView
      :status="viewStatus"
      mode="table"
      :error="error"
      :empty-title="t('adminCommerce.noReturns')"
      preserve-content-on-error
      @retry="loadOrders()"
    >
      <DataTableShell :aria-label="t('adminCommerce.returnsTitle')" :busy="status === 'updating'">
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
            <tr v-for="order in returnOrders" :key="order.id" :data-order-id="order.id">
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
                    v-if="hasAdminOrderAction(order.status, 'approveReturn')"
                    type="primary"
                    :icon="Check"
                    :loading="isPending(order.id)"
                    :disabled="isPending(order.id)"
                    @click="approveReturn(order)"
                  >
                    {{ t('adminCommerce.approveReturn') }}
                  </el-button>
                  <el-button
                    v-if="hasAdminOrderAction(order.status, 'refundReturn')"
                    type="danger"
                    plain
                    :icon="Wallet"
                    :loading="isPending(order.id)"
                    :disabled="isPending(order.id)"
                    @click="refundReturn(order)"
                  >
                    {{ t('adminCommerce.refund') }}
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

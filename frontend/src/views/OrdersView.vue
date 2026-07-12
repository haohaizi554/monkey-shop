<script setup lang="ts">
import { RefreshRight } from '@element-plus/icons-vue'
import { computed, onMounted, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import * as ordersApi from '@/api/orders'
import OrderStatusTimeline from '@/components/order/OrderStatusTimeline.vue'
import ProductImage from '@/components/ProductImage.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type { Order } from '@/types'
import { dateTime, money, orderStatusKey } from '@/utils/format'

const { t } = useI18n()
const notify = useNotify()
const orderResource = useAsyncState<Order[]>({ timeoutMs: 20000 })
const actionInProgress = reactive<Record<number, string | undefined>>({})
const actionErrors = reactive<Record<number, string | undefined>>({})

const orders = computed(() => orderResource.data.value ?? [])

const statusAliases: Record<string, string> = {
  CREATED: 'PAYMENT_PENDING',
  PENDING: 'PAYMENT_PENDING',
  PAYMENT_PENDING: 'PAYMENT_PENDING',
  PAYMENT_COMPLETED: 'PAID',
  PICKED_UP: 'SHIPPED',
  IN_TRANSIT: 'SHIPPED',
  OUT_FOR_DELIVERY: 'SHIPPED',
  SIGNED: 'COMPLETED',
}

function normalizedStatus(status: string): string {
  const normalized = orderStatusKey(status)
  return statusAliases[normalized] ?? normalized
}

function actionKey(action: string, orderId: number): string {
  return `${action}:${orderId}`
}

function isActionPending(orderId: number, action: string): boolean {
  return actionInProgress[orderId] === actionKey(action, orderId)
}

function isOrderUpdating(orderId: number): boolean {
  return Boolean(actionInProgress[orderId])
}

function canViewLogistics(order: Order): boolean {
  return [
    'PAID',
    'PARTIALLY_SHIPPED',
    'SHIPPED',
    'PARTIALLY_RECEIVED',
    'COMPLETED',
    'RETURN_REQUESTED',
    'WAITING_RETURN_SHIPMENT',
    'RETURN_SHIPPING',
  ].includes(normalizedStatus(order.status))
}

function canHideOrder(order: Order): boolean {
  return ['COMPLETED', 'REFUNDED'].includes(normalizedStatus(order.status))
}

async function loadOrders() {
  await orderResource.load(() => ordersApi.myOrders(), {
    isEmpty: (items) => items.length === 0,
    preserveData: true,
  })
}

async function runAction(
  orderId: number,
  action: string,
  confirmationKey: string,
  operation: () => Promise<unknown>,
) {
  if (isOrderUpdating(orderId)) {
    return
  }

  actionInProgress[orderId] = actionKey(action, orderId)
  delete actionErrors[orderId]
  try {
    const confirmed = await notify.confirm({
      content: t(confirmationKey),
      type: action === 'hide' ? 'warning' : 'info',
    })
    if (!confirmed) {
      return
    }

    await operation()
    notify.success(t('common.updated'), { key: `order:${orderId}:${action}:success` })
    await loadOrders()
  } catch {
    actionErrors[orderId] = t('common.unableToUpdateOrder')
  } finally {
    delete actionInProgress[orderId]
  }
}

onMounted(() => {
  void loadOrders()
})
</script>

<template>
  <div class="route-view orders-view">
    <PageHeader :title="$t('nav.orders')">
      <template #actions>
        <el-button
          :icon="RefreshRight"
          :loading="orderResource.status.value === 'updating'"
          @click="loadOrders"
        >
          {{ $t('common.refresh') }}
        </el-button>
      </template>
    </PageHeader>

    <AsyncStateView
      :status="orderResource.status.value"
      :error="orderResource.error.value"
      :empty-title="$t('common.noOrders')"
      @retry="loadOrders"
    >
      <template #error>
        <div class="orders-view__load-error" role="alert">
          <p>{{ $t('common.unableToLoadOrders') }}</p>
          <el-button :icon="RefreshRight" @click="loadOrders">
            {{ $t('common.retry') }}
          </el-button>
        </div>
      </template>

      <DataTableShell
        :aria-label="$t('common.orders')"
        :busy="orderResource.status.value === 'updating'"
      >
        <div class="order-list">
          <article v-for="order in orders" :key="order.id" class="order-row">
            <ProductImage :src="order.productImage" :alt="order.productName" />

            <div class="order-main">
              <header class="order-title">
                <div>
                  <h2>{{ order.productName }}</h2>
                  <p>{{ order.orderNo }} · {{ dateTime(order.createTime) }}</p>
                </div>
                <strong>{{ money(order.price) }}</strong>
              </header>

              <p class="order-address">
                {{ order.receiverName }} · {{ order.receiverPhone }} · {{ order.addressSnapshot }}
              </p>

              <OrderStatusTimeline
                :current-status="order.status"
                :timestamps="{
                  created: order.createTime,
                  shipped: order.shippingTime,
                }"
              />

              <p v-if="actionErrors[order.id]" class="task-error" role="alert">
                {{ actionErrors[order.id] }}
              </p>
            </div>

            <div class="row-actions">
              <el-button
                v-if="normalizedStatus(order.status) === 'SHIPPED'"
                type="primary"
                :loading="isActionPending(order.id, 'receive')"
                :disabled="isOrderUpdating(order.id)"
                @click="
                  runAction(order.id, 'receive', 'common.receiveOrderConfirm', () =>
                    ordersApi.receiveOrder(order.id),
                  )
                "
              >
                {{ $t('common.receive') }}
              </el-button>

              <el-button
                v-if="normalizedStatus(order.status) === 'COMPLETED'"
                plain
                :loading="isActionPending(order.id, 'return')"
                :disabled="isOrderUpdating(order.id)"
                @click="
                  runAction(order.id, 'return', 'common.requestReturnConfirm', () =>
                    ordersApi.applyReturn(order.id),
                  )
                "
              >
                {{ $t('common.return') }}
              </el-button>

              <RouterLink
                v-if="normalizedStatus(order.status) === 'COMPLETED'"
                :to="`/orders/${order.id}/review`"
              >
                <el-button plain :disabled="isOrderUpdating(order.id)">
                  {{ $t('common.review') }}
                </el-button>
              </RouterLink>

              <RouterLink
                v-if="normalizedStatus(order.status) === 'PAYMENT_PENDING'"
                :to="`/payment/${order.id}`"
              >
                <el-button plain :disabled="isOrderUpdating(order.id)">
                  {{ $t('common.payment') }}
                </el-button>
              </RouterLink>

              <RouterLink v-if="canViewLogistics(order)" :to="`/logistics/${order.id}`">
                <el-button plain :disabled="isOrderUpdating(order.id)">
                  {{ $t('common.logistics') }}
                </el-button>
              </RouterLink>

              <el-button
                v-if="normalizedStatus(order.status) === 'WAITING_RETURN_SHIPMENT'"
                plain
                :loading="isActionPending(order.id, 'ship-return')"
                :disabled="isOrderUpdating(order.id)"
                @click="
                  runAction(order.id, 'ship-return', 'common.shipReturnConfirm', () =>
                    ordersApi.shipReturn(order.id),
                  )
                "
              >
                {{ $t('common.shipReturn') }}
              </el-button>

              <el-button
                v-if="canHideOrder(order)"
                type="danger"
                plain
                :loading="isActionPending(order.id, 'hide')"
                :disabled="isOrderUpdating(order.id)"
                @click="
                  runAction(order.id, 'hide', 'common.hideOrderConfirm', () =>
                    ordersApi.hideOrder(order.id),
                  )
                "
              >
                {{ $t('common.hideOrder') }}
              </el-button>
            </div>
          </article>
        </div>
      </DataTableShell>
    </AsyncStateView>
  </div>
</template>

<style scoped>
.orders-view {
  display: grid;
  gap: 18px;
}

.orders-view__load-error {
  align-items: flex-start;
  display: grid;
  gap: 12px;
  justify-items: start;
}

.orders-view__load-error p,
.order-address,
.task-error {
  margin: 0;
}

.order-list {
  display: grid;
}

.order-row {
  align-items: start;
  border-bottom: 1px solid var(--el-border-color-lighter);
  display: grid;
  gap: 16px;
  grid-template-columns: 112px minmax(0, 1fr) minmax(140px, auto);
  padding: 18px 0;
}

.order-row:last-child {
  border-bottom: 0;
}

.order-row :deep(.product-image) {
  aspect-ratio: 1;
  height: auto;
  width: 112px;
}

.order-main {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.order-title {
  align-items: start;
  display: flex;
  gap: 14px;
  justify-content: space-between;
}

.order-title h2 {
  font-size: 1rem;
  margin: 0;
  overflow-wrap: anywhere;
}

.order-title p,
.order-address {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.order-title p {
  margin: 4px 0 0;
}

.order-title strong {
  flex: 0 0 auto;
}

.row-actions {
  align-content: start;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

.row-actions :deep(.el-button) {
  margin-left: 0;
  min-height: 40px;
}

.task-error {
  color: var(--el-color-danger);
  font-size: 13px;
}

@media (max-width: 860px) {
  .order-row {
    grid-template-columns: 88px minmax(0, 1fr);
  }

  .order-row :deep(.product-image) {
    width: 88px;
  }

  .row-actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }
}

@media (max-width: 520px) {
  .order-row {
    grid-template-columns: 72px minmax(0, 1fr);
  }

  .order-row :deep(.product-image) {
    width: 72px;
  }

  .order-title {
    align-items: start;
    display: grid;
  }

  .row-actions :deep(.el-button) {
    min-height: 44px;
  }
}
</style>

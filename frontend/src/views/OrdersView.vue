<script setup lang="ts">
import {
  ArrowDown,
  ArrowUp,
  Box,
  CreditCard,
  Location,
  RefreshRight,
  Star,
  Van,
} from '@element-plus/icons-vue'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import * as ordersApi from '@/api/orders'
import type { OrderSummary } from '@/api/orders'
import type { PageEnvelope } from '@/api/page'
import MascotState from '@/components/mascot/MascotState.vue'
import OrderStatusTimeline from '@/components/order/OrderStatusTimeline.vue'
import ProductImage from '@/components/ProductImage.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import ConfirmAction from '@/components/ui/ConfirmAction.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import type { AsyncStatus } from '@/composables/useAsyncState'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type { OrderShipment } from '@/types'
import { dateTime, money } from '@/utils/format'
import {
  consumerOrderStatusTone,
  hasConsumerOrderAction,
  normalizeConsumerOrderStatus,
  type ConsumerOrderStatus,
} from '@/utils/orderActions'

type OrderFilter = 'all' | 'payment' | 'shipping' | 'returns' | 'completed'

interface ShipmentState {
  status: AsyncStatus
  items: OrderShipment[]
  error: string | null
}

const router = useRouter()
const { t } = useI18n()
const notify = useNotify()
const orderResource = useAsyncState<PageEnvelope<OrderSummary>>({ timeoutMs: 20000 })
const actionInProgress = reactive<Record<number, string | undefined>>({})
const actionErrors = reactive<Record<number, string | undefined>>({})
const shipmentStates = reactive<Record<number, ShipmentState>>({})
const expandedOrders = reactive(new Set<number>())
const activeFilter = ref<OrderFilter>('all')
const currentPage = ref(0)
const pageSize = 10

const orderPage = computed(() => orderResource.data.value)
const orders = computed(() => orderPage.value?.content ?? [])
const filterOptions = computed(() => [
  { label: t('orders.filters.all'), value: 'all' },
  { label: t('orders.filters.payment'), value: 'payment' },
  { label: t('orders.filters.shipping'), value: 'shipping' },
  { label: t('orders.filters.returns'), value: 'returns' },
  { label: t('orders.filters.completed'), value: 'completed' },
])
const statusesByFilter: Record<OrderFilter, string | undefined> = {
  all: undefined,
  payment: 'PENDING_PAYMENT',
  shipping: 'PAID,PARTIALLY_SHIPPED,SHIPPED,PARTIALLY_RECEIVED',
  returns: 'RETURN_REQUESTED,WAITING_RETURN_SHIPMENT,RETURN_SHIPPING',
  completed: 'COMPLETED,REFUNDED',
}

function openReview(order: OrderSummary) {
  const skuId = order.lines?.[0]?.skuId ?? order.productId
  void router.push({
    path: `/orders/${order.id}/review`,
    query: { skuId: String(skuId) },
  })
}

function actionKey(action: string, targetId: number): string {
  return `${action}:${targetId}`
}

function isActionPending(orderId: number, action: string, targetId = orderId): boolean {
  return actionInProgress[orderId] === actionKey(action, targetId)
}

function isOrderUpdating(orderId: number): boolean {
  return Boolean(actionInProgress[orderId])
}

function hasAction(order: OrderSummary, action: Parameters<typeof hasConsumerOrderAction>[1]) {
  return hasConsumerOrderAction(order.status, action)
}

function statusLabel(status: string): string {
  const keys: Record<ConsumerOrderStatus, string> = {
    PAYMENT_PENDING: 'orders.status.paymentPending',
    PAID: 'orders.status.paid',
    PARTIALLY_SHIPPED: 'orders.status.partiallyShipped',
    SHIPPED: 'orders.status.shipped',
    PARTIALLY_RECEIVED: 'orders.status.partiallyReceived',
    COMPLETED: 'orders.status.completed',
    RETURN_REQUESTED: 'orders.status.returnRequested',
    WAITING_RETURN_SHIPMENT: 'orders.status.waitingReturnShipment',
    RETURN_SHIPPING: 'orders.status.returnShipping',
    REFUNDED: 'orders.status.refunded',
    CANCELLED: 'orders.status.cancelled',
    UNKNOWN: 'orders.status.processing',
  }
  return t(keys[normalizeConsumerOrderStatus(status)])
}

function amount(value: string | number | undefined): string {
  return money(value ?? 0)
}

function maskPhone(value: string): string {
  const normalized = value.trim()
  return normalized.length >= 7
    ? `${normalized.slice(0, 3)}****${normalized.slice(-4)}`
    : normalized
}

function shipmentState(orderId: number): ShipmentState {
  shipmentStates[orderId] ??= { status: 'idle', items: [], error: null }
  return shipmentStates[orderId]
}

function detailsId(orderId: number): string {
  return `order-details-${orderId}`
}

async function loadOrders(page = currentPage.value) {
  currentPage.value = page
  await orderResource.load(
    ({ signal }) =>
      ordersApi.myOrderPage({
        page,
        size: pageSize,
        status: statusesByFilter[activeFilter.value],
        signal,
      }),
    {
      isEmpty: (result) => result.content.length === 0,
      preserveData: true,
    },
  )
}

function changePage(page: number) {
  expandedOrders.clear()
  void loadOrders(page - 1)
}

async function loadShipments(orderId: number, force = false) {
  const state = shipmentState(orderId)
  if (!force && ['loading', 'success', 'empty'].includes(state.status)) {
    return
  }
  state.status = state.items.length > 0 ? 'updating' : 'loading'
  state.error = null
  try {
    state.items = await ordersApi.orderShipments(orderId)
    state.status = state.items.length > 0 ? 'success' : 'empty'
  } catch {
    state.status = state.items.length > 0 ? 'success' : 'error'
    state.error = 'orders.shipmentsLoadFailed'
  }
}

async function toggleOrderDetails(order: OrderSummary) {
  if (expandedOrders.has(order.id)) {
    expandedOrders.delete(order.id)
    return
  }
  shipmentState(order.id)
  expandedOrders.add(order.id)
  if (normalizeConsumerOrderStatus(order.status) !== 'PAYMENT_PENDING') {
    await loadShipments(order.id)
  }
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
      type: action === 'return' || action === 'ship-return' ? 'warning' : 'info',
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

async function receiveShipment(orderId: number, shipment: OrderShipment) {
  if (isOrderUpdating(orderId)) {
    return
  }
  actionInProgress[orderId] = actionKey('receive-shipment', shipment.id)
  delete actionErrors[orderId]
  try {
    const confirmed = await notify.confirm({
      content: t('orders.receiveShipmentConfirm'),
      type: 'info',
    })
    if (!confirmed) {
      return
    }
    const received = await ordersApi.receiveShipment(shipment.id)
    const state = shipmentState(orderId)
    state.items = state.items.map((item) => (item.id === received.id ? received : item))
    notify.success(t('orders.shipmentReceived'), {
      key: `order:${orderId}:shipment:${shipment.id}:received`,
    })
    await loadOrders()
  } catch {
    actionErrors[orderId] = t('orders.receiveShipmentFailed')
  } finally {
    delete actionInProgress[orderId]
  }
}

async function hideOrder(order: OrderSummary) {
  delete actionErrors[order.id]
  try {
    await ordersApi.hideOrder(order.id)
    notify.success(t('orders.hidden'), { key: `order:${order.id}:hidden` })
    await loadOrders()
  } catch (error) {
    actionErrors[order.id] = t('common.unableToUpdateOrder')
    throw error
  }
}

onMounted(() => {
  void loadOrders()
})

watch(activeFilter, () => {
  expandedOrders.clear()
  void loadOrders(0)
})

onUnmounted(() => orderResource.cancel())
</script>

<template>
  <div class="route-view orders-view">
    <PageHeader :title="$t('nav.orders')" :description="$t('orders.hint')">
      <template #actions>
        <el-button
          :icon="RefreshRight"
          :loading="orderResource.status.value === 'updating'"
          @click="loadOrders()"
        >
          {{ $t('common.refresh') }}
        </el-button>
      </template>
    </PageHeader>

    <div class="orders-toolbar">
      <el-segmented
        v-model="activeFilter"
        :options="filterOptions"
        :aria-label="$t('orders.filterLabel')"
      />
      <span>{{ $t('orders.orderCount', { count: orderPage?.totalElements ?? 0 }) }}</span>
    </div>

    <AsyncStateView
      :status="orderResource.status.value"
      :error="orderResource.error.value"
      :empty-title="$t('common.noOrders')"
      @retry="loadOrders()"
    >
      <template #error>
        <div class="orders-view__load-error" role="alert">
          <p>{{ $t('common.unableToLoadOrders') }}</p>
          <el-button :icon="RefreshRight" @click="loadOrders()">
            {{ $t('common.retry') }}
          </el-button>
        </div>
      </template>

      <template #empty>
        <section class="orders-empty" role="status">
          <MascotState pose="shoppingBag" size="md" :alt="$t('orders.emptyMascotAlt')" />
          <h2>{{ $t('common.noOrders') }}</h2>
          <p>{{ $t('orders.emptyHint') }}</p>
          <RouterLink class="orders-empty__action" to="/shop">
            {{ $t('common.backToShop') }}
          </RouterLink>
        </section>
      </template>

      <DataTableShell
        :aria-label="$t('common.orders')"
        :busy="orderResource.status.value === 'updating'"
      >
        <div v-if="orders.length" class="order-list">
          <article v-for="order in orders" :key="order.id" class="order-row">
            <div class="order-row__summary">
              <ProductImage :src="order.productImage" :alt="order.productName" />

              <div class="order-main">
                <div class="order-title">
                  <div>
                    <p class="order-number">{{ order.orderNo }}</p>
                    <h2>{{ order.productName }}</h2>
                  </div>
                  <div class="order-title__amount">
                    <el-tag :type="consumerOrderStatusTone(order.status)" disable-transitions>
                      {{ statusLabel(order.status) }}
                    </el-tag>
                    <strong>{{ amount(order.price) }}</strong>
                  </div>
                </div>

                <div class="order-meta">
                  <span>{{ dateTime(order.createTime) }}</span>
                  <span v-if="order.shopId">{{ $t('checkout.shop') }} {{ order.shopId }}</span>
                  <span>{{ order.receiverName }} / {{ maskPhone(order.receiverPhone) }}</span>
                </div>

                <p v-if="actionErrors[order.id]" class="task-error" role="alert">
                  {{ actionErrors[order.id] }}
                </p>
              </div>

              <div class="row-actions">
                <el-button
                  v-if="hasAction(order, 'pay')"
                  type="primary"
                  :icon="CreditCard"
                  :disabled="isOrderUpdating(order.id)"
                  @click="router.push(`/payment/${order.id}`)"
                >
                  {{ $t('common.payment') }}
                </el-button>

                <el-button
                  v-if="hasAction(order, 'receive')"
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
                  v-if="hasAction(order, 'requestReturn')"
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

                <el-button
                  v-if="hasAction(order, 'review')"
                  plain
                  :icon="Star"
                  :disabled="isOrderUpdating(order.id)"
                  @click="openReview(order)"
                >
                  {{ $t('common.review') }}
                </el-button>

                <el-button
                  v-if="hasAction(order, 'logistics')"
                  plain
                  :icon="Van"
                  :disabled="isOrderUpdating(order.id)"
                  @click="router.push(`/logistics/${order.id}`)"
                >
                  {{ $t('common.logistics') }}
                </el-button>

                <el-button
                  v-if="hasAction(order, 'shipReturn')"
                  plain
                  :icon="Box"
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

                <ConfirmAction
                  v-if="hasAction(order, 'hide')"
                  :content="$t('common.hideOrderConfirm')"
                  :action="() => hideOrder(order)"
                  :disabled="isOrderUpdating(order.id)"
                >
                  {{ $t('common.hideOrder') }}
                </ConfirmAction>
              </div>

              <el-button
                class="order-details-toggle"
                text
                :icon="expandedOrders.has(order.id) ? ArrowUp : ArrowDown"
                :aria-label="`${$t('orders.viewDetails')} ${order.orderNo}`"
                :aria-expanded="expandedOrders.has(order.id)"
                :aria-controls="detailsId(order.id)"
                @click="toggleOrderDetails(order)"
              >
                {{ expandedOrders.has(order.id) ? $t('orders.hideDetails') : $t('orders.details') }}
              </el-button>
            </div>

            <section
              v-if="expandedOrders.has(order.id)"
              :id="detailsId(order.id)"
              class="order-details"
            >
              <dl class="order-facts">
                <div>
                  <dt>{{ $t('checkout.originalAmount') }}</dt>
                  <dd>{{ amount(order.originalAmount ?? order.price) }}</dd>
                </div>
                <div>
                  <dt>{{ $t('checkout.discount') }}</dt>
                  <dd>{{ amount(order.discountAmount) }}</dd>
                </div>
                <div>
                  <dt>{{ $t('checkout.payable') }}</dt>
                  <dd>{{ amount(order.price) }}</dd>
                </div>
                <div class="order-facts__address">
                  <dt>
                    <el-icon aria-hidden="true"><Location /></el-icon>
                    {{ $t('common.selectAddress') }}
                  </dt>
                  <dd>{{ order.addressSnapshot }}</dd>
                </div>
              </dl>

              <OrderStatusTimeline
                :current-status="order.status"
                :timestamps="{
                  created: order.createTime,
                  shipped: order.shippingTime,
                }"
              />

              <section
                v-if="normalizeConsumerOrderStatus(order.status) !== 'PAYMENT_PENDING'"
                class="shipment-section"
                :aria-label="$t('orders.shipments')"
              >
                <header>
                  <h3>{{ $t('orders.shipments') }}</h3>
                  <el-button
                    text
                    :icon="RefreshRight"
                    :loading="shipmentState(order.id).status === 'updating'"
                    @click="loadShipments(order.id, true)"
                  >
                    {{ $t('common.refresh') }}
                  </el-button>
                </header>

                <AsyncStateView
                  :status="shipmentState(order.id).status"
                  :error="shipmentState(order.id).error"
                  mode="grid"
                  @retry="loadShipments(order.id, true)"
                >
                  <template #empty>
                    <p class="shipment-empty">{{ $t('orders.noShipments') }}</p>
                  </template>

                  <div class="shipment-list">
                    <article
                      v-for="shipment in shipmentState(order.id).items"
                      :key="shipment.id"
                      class="order-shipment"
                    >
                      <div class="order-shipment__heading">
                        <div>
                          <strong>{{ shipment.trackingNo }}</strong>
                          <span>{{ shipment.carrier }} / {{ shipment.shipmentNo }}</span>
                        </div>
                        <el-tag
                          :type="shipment.status === 'RECEIVED' ? 'success' : 'primary'"
                          disable-transitions
                        >
                          {{
                            shipment.status === 'RECEIVED'
                              ? $t('orders.shipmentReceivedStatus')
                              : $t('orders.shipmentShippedStatus')
                          }}
                        </el-tag>
                      </div>
                      <ul>
                        <li v-for="line in shipment.lines" :key="line.skuId">
                          <span>{{ line.productName }}</span>
                          <strong>{{ $t('common.quantity') }} {{ line.quantity }}</strong>
                        </li>
                      </ul>
                      <el-button
                        v-if="shipment.status === 'SHIPPED'"
                        plain
                        :loading="isActionPending(order.id, 'receive-shipment', shipment.id)"
                        :disabled="isOrderUpdating(order.id)"
                        @click="receiveShipment(order.id, shipment)"
                      >
                        {{ $t('orders.receiveShipment') }}
                      </el-button>
                    </article>
                  </div>
                </AsyncStateView>
              </section>
            </section>
          </article>
        </div>

        <section v-else class="orders-filter-empty" role="status">
          <MascotState pose="search" size="sm" :alt="$t('orders.filterEmptyMascotAlt')" />
          <p>{{ $t('orders.filterEmpty') }}</p>
        </section>
      </DataTableShell>
      <el-pagination
        v-if="(orderPage?.totalElements ?? 0) > pageSize"
        class="orders-pagination"
        background
        layout="prev, pager, next, total"
        :current-page="currentPage + 1"
        :page-size="pageSize"
        :total="orderPage?.totalElements ?? 0"
        @current-change="changePage"
      />
    </AsyncStateView>
  </div>
</template>

<style scoped>
.orders-view {
  display: grid;
  gap: var(--space-5);
}

.orders-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--color-line);
}

.orders-pagination {
  min-height: 32px;
  justify-content: center;
  overflow-x: auto;
}

.orders-toolbar > span {
  flex: 0 0 auto;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.orders-view__load-error {
  display: grid;
  gap: var(--space-3);
  justify-items: start;
}

.orders-view__load-error p,
.task-error {
  margin: 0;
}

.order-list {
  display: grid;
  gap: 0;
}

.order-row {
  display: block;
  min-width: 0;
  padding: var(--space-5) 0;
  border: 0;
  border-bottom: 1px solid var(--color-line);
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.order-row:last-child {
  border-bottom: 0;
}

.order-row__summary {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) minmax(180px, auto);
  gap: var(--space-4);
  align-items: start;
}

.order-row__summary :deep(.product-image) {
  width: 96px;
  height: 96px;
  aspect-ratio: 1;
}

.order-main {
  display: grid;
  gap: var(--space-3);
  min-width: 0;
}

.order-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.order-title h2 {
  margin: var(--space-1) 0 0;
  overflow-wrap: anywhere;
  font-size: var(--text-base);
}

.order-number {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-xs);
  font-weight: 650;
}

.order-title__amount {
  display: grid;
  flex: 0 0 auto;
  gap: var(--space-2);
  justify-items: end;
}

.order-title__amount strong {
  font-size: var(--text-lg);
}

.order-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--space-2);
}

.row-actions :deep(.el-button) {
  min-height: 38px;
  margin: 0;
}

.order-details-toggle {
  grid-column: 2;
  justify-self: start;
  margin: 0;
}

.task-error {
  color: var(--color-danger);
  font-size: var(--text-sm);
}

.order-details {
  display: grid;
  gap: var(--space-5);
  margin-top: var(--space-4);
  padding: var(--space-5) 0 0 112px;
  border-top: 1px dashed var(--color-line);
}

.order-facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(120px, 0.5fr)) minmax(240px, 1.5fr);
  gap: 0;
  margin: 0;
}

.order-facts div {
  min-width: 0;
  padding: 0 var(--space-4);
  border-right: 1px solid var(--color-line);
}

.order-facts div:first-child {
  padding-left: 0;
}

.order-facts div:last-child {
  border-right: 0;
}

.order-facts dt {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.order-facts dd {
  margin: var(--space-1) 0 0;
  overflow-wrap: anywhere;
  font-weight: 700;
}

.shipment-section {
  display: grid;
  gap: var(--space-3);
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-line);
}

.shipment-section > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
}

.shipment-section h3 {
  margin: 0;
  font-size: var(--text-base);
}

.shipment-list {
  display: grid;
}

.order-shipment {
  display: grid;
  grid-template-columns: minmax(180px, 0.7fr) minmax(220px, 1fr) auto;
  gap: var(--space-4);
  align-items: center;
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--color-line);
}

.order-shipment:last-child {
  border-bottom: 0;
}

.order-shipment__heading,
.order-shipment__heading > div {
  display: grid;
  gap: var(--space-1);
}

.order-shipment__heading {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
}

.order-shipment__heading span {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.order-shipment ul {
  display: grid;
  gap: var(--space-1);
  margin: 0;
  padding: 0;
  list-style: none;
}

.order-shipment li {
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
  font-size: var(--text-sm);
}

.shipment-empty {
  margin: 0;
  padding: var(--space-4) 0;
  color: var(--color-text-muted);
}

.orders-empty,
.orders-filter-empty {
  display: grid;
  justify-items: center;
  gap: var(--space-2);
  min-height: 320px;
  align-content: center;
  text-align: center;
}

.orders-empty h2,
.orders-empty p,
.orders-filter-empty p {
  margin: 0;
}

.orders-empty p,
.orders-filter-empty p {
  color: var(--color-text-muted);
}

.orders-empty__action {
  display: inline-grid;
  min-height: 40px;
  place-items: center;
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-control);
  background: var(--color-brand);
  color: var(--color-text-inverse);
  font-weight: 700;
  text-decoration: none;
}

@media (max-width: 980px) {
  .order-row__summary {
    grid-template-columns: 88px minmax(0, 1fr);
  }

  .order-row__summary :deep(.product-image) {
    width: 88px;
    height: 88px;
  }

  .row-actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }

  .order-details-toggle {
    grid-column: 1 / -1;
  }

  .order-details {
    padding-left: 0;
  }

  .order-facts {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: var(--space-3);
  }

  .order-facts__address {
    grid-column: 1 / -1;
  }

  .order-shipment {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .orders-toolbar {
    display: grid;
  }

  .orders-toolbar :deep(.el-segmented) {
    width: 100%;
    overflow-x: auto;
  }

  .orders-toolbar > span {
    justify-self: end;
  }

  .order-row__summary {
    grid-template-columns: 72px minmax(0, 1fr);
    gap: var(--space-3);
  }

  .order-row__summary :deep(.product-image) {
    width: 72px;
    height: 72px;
  }

  .order-title {
    display: grid;
  }

  .order-title__amount {
    grid-template-columns: auto auto;
    align-items: center;
    justify-content: start;
    justify-items: start;
  }

  .row-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .row-actions > *,
  .row-actions :deep(.el-button),
  .row-actions :deep(.confirm-action) {
    width: 100%;
    min-height: 44px;
  }

  .order-facts {
    grid-template-columns: 1fr;
  }

  .order-facts div {
    display: flex;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-2) 0;
    border-right: 0;
    border-bottom: 1px solid var(--color-line);
  }

  .order-facts dd {
    margin: 0;
    text-align: right;
  }

  .order-facts__address {
    display: grid !important;
  }
}
</style>

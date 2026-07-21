<script setup lang="ts">
import { Plus, RefreshRight } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { isPositiveApiId, normalizeApiId, sameApiId, type ApiId } from '@/api/ids'
import * as ordersApi from '@/api/orders'
import type { OrderShipmentPayload } from '@/api/orders'
import AdminCommerceNav from '@/components/admin/AdminCommerceNav.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { useAdminOrders } from '@/composables/useAdminOrders'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type { LogisticsCarrier, OrderShipment } from '@/types'
import { hasAdminOrderAction, normalizeAdminOrderStatus } from '@/utils/adminOrderActions'
import { dateTime } from '@/utils/format'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'
import { shipmentLinesForOrder } from '@/utils/orderLineContract'

defineOptions({ name: 'LogisticsOperationsView' })

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const notify = useNotify()
const { orders, status: orderStatus, error: orderError, loadOrders } = useAdminOrders()
const shipmentState = useAsyncState<OrderShipment[]>({ preserveData: true })
const selectedOrderId = ref<ApiId>()
const carrier = ref<LogisticsCarrier>('SF')
const trackingNo = ref('')
const createPending = ref(false)

const logisticsOrders = computed(() =>
  orders.value.filter(
    (order) =>
      hasAdminOrderAction(order.status, 'ship') ||
      hasAdminOrderAction(order.status, 'viewShipments'),
  ),
)
const selectedOrder = computed(() =>
  orders.value.find((order) => sameApiId(order.id, selectedOrderId.value)),
)
const shipments = computed(() => shipmentState.data.value ?? [])
const canCreate = computed(
  () =>
    Boolean(selectedOrder.value) &&
    hasAdminOrderAction(selectedOrder.value?.status ?? '', 'ship') &&
    trackingNo.value.trim().length > 0 &&
    !createPending.value,
)

async function loadShipments(orderId: ApiId | undefined = selectedOrderId.value) {
  const normalizedOrderId = normalizeApiId(orderId)
  if (!isPositiveApiId(normalizedOrderId)) {
    shipmentState.reset()
    return
  }
  await shipmentState.load(() => ordersApi.adminOrderShipments(normalizedOrderId), {
    preserveData: true,
    isEmpty: (rows) => rows.length === 0,
  })
}

async function selectOrder(orderId: ApiId | undefined) {
  if (!isPositiveApiId(orderId)) {
    selectedOrderId.value = undefined
    shipmentState.reset()
    await router.replace({ query: {} })
    return
  }
  selectedOrderId.value = orderId
  const queryId = String(orderId)
  if (route.query.orderId !== queryId) {
    await router.replace({ query: { ...route.query, orderId: queryId } })
    return
  }
  await loadShipments(orderId)
}

async function createShipment() {
  const order = selectedOrder.value
  if (!order || !canCreate.value || createPending.value) {
    return
  }

  const payload: OrderShipmentPayload = {
    carrier: carrier.value,
    trackingNo: trackingNo.value.trim(),
    lines: shipmentLinesForOrder(order),
  }
  createPending.value = true
  try {
    const intent = getIdempotencyIntent(`admin:shipment:${order.id}`, payload)
    const created = await ordersApi.createShipment(order.id, payload, intent.key)
    intent.complete()
    shipmentState.data.value = [...shipments.value, created]
    shipmentState.status.value = 'success'
    shipmentState.error.value = null
    trackingNo.value = ''
    notify.success(t('adminCommerce.shipmentCreated'), {
      key: `shipment:create:${created.id}`,
    })
    void loadOrders()
  } catch (caught) {
    notify.fromApiError(caught, 'common.unableToUpdateOrder')
  } finally {
    createPending.value = false
  }
}

watch(
  () => route.query.orderId,
  (value) => {
    const id = normalizeApiId(value)
    if (isPositiveApiId(id)) {
      selectedOrderId.value = id
      void loadShipments(id)
    }
  },
  { immediate: true },
)

watch(
  logisticsOrders,
  (rows) => {
    if (!selectedOrderId.value && rows[0]) {
      void selectOrder(rows[0].id)
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="route-view commerce-page">
    <PageHeader
      :eyebrow="t('adminCommerce.workspace')"
      :title="t('adminCommerce.logisticsTitle')"
      :description="t('adminCommerce.logisticsDescription')"
    >
      <template #actions>
        <el-button :icon="RefreshRight" :loading="orderStatus === 'updating'" @click="loadOrders">
          {{ t('adminCommerce.refreshOrders') }}
        </el-button>
      </template>
    </PageHeader>

    <AdminCommerceNav />

    <AsyncStateView
      :status="orderStatus"
      mode="form"
      :error="orderError"
      preserve-content-on-error
      @retry="loadOrders"
    >
      <section class="commerce-section" :aria-labelledby="'shipment-create-title'">
        <div class="commerce-section__heading">
          <div>
            <h2 id="shipment-create-title">{{ t('adminCommerce.createShipment') }}</h2>
          </div>
          <span v-if="selectedOrder" class="commerce-inline-state">
            {{ t('adminCommerce.selectedOrder') }}
            <strong>{{ selectedOrder.orderNo }}</strong>
            <StatusTag :status="normalizeAdminOrderStatus(selectedOrder.status)" />
          </span>
        </div>

        <div class="commerce-form-grid" data-columns="3">
          <div class="commerce-field">
            <span>{{ t('adminCommerce.selectOrder') }}</span>
            <el-select
              id="shipment-order"
              v-model="selectedOrderId"
              filterable
              :aria-label="t('adminCommerce.selectOrder')"
              @change="selectOrder"
            >
              <el-option
                v-for="order in logisticsOrders"
                :key="order.id"
                :value="order.id"
                :label="`${order.orderNo} - ${order.productName}`"
              />
            </el-select>
          </div>
          <div class="commerce-field">
            <span>{{ t('adminCommerce.carrier') }}</span>
            <el-select
              id="shipment-carrier"
              v-model="carrier"
              :aria-label="t('adminCommerce.carrier')"
            >
              <el-option label="SF Express" value="SF" />
              <el-option label="ZTO Express" value="ZTO" />
              <el-option label="YTO Express" value="YTO" />
            </el-select>
          </div>
          <div class="commerce-field">
            <span>{{ t('adminCommerce.trackingNumber') }}</span>
            <el-input
              id="shipment-tracking"
              v-model="trackingNo"
              :aria-label="t('adminCommerce.trackingNumber')"
              @keyup.enter="createShipment"
            />
          </div>
        </div>

        <div class="commerce-actions commerce-actions--end">
          <el-button
            type="primary"
            :icon="Plus"
            :loading="createPending"
            :disabled="!canCreate"
            @click="createShipment"
          >
            {{ t('adminCommerce.createShipment') }}
          </el-button>
        </div>
      </section>

      <section class="commerce-section" :aria-labelledby="'shipment-list-title'">
        <div class="commerce-section__heading">
          <div>
            <h2 id="shipment-list-title">{{ t('adminCommerce.shipments') }}</h2>
          </div>
          <el-button
            v-if="selectedOrderId"
            :icon="RefreshRight"
            :loading="shipmentState.isLoading.value"
            @click="loadShipments()"
          >
            {{ t('common.refresh') }}
          </el-button>
        </div>

        <AsyncStateView
          :status="shipmentState.status.value"
          mode="table"
          :error="shipmentState.error.value"
          :empty-title="t('adminCommerce.noShipments')"
          preserve-content-on-error
          @retry="loadShipments()"
        >
          <template #idle>
            <p class="commerce-inline-state">{{ t('adminCommerce.selectOrder') }}</p>
          </template>
          <DataTableShell
            :aria-label="t('adminCommerce.shipments')"
            :busy="shipmentState.status.value === 'updating'"
          >
            <table class="commerce-table">
              <thead>
                <tr>
                  <th scope="col">{{ t('adminCommerce.shipmentNo') }}</th>
                  <th scope="col">{{ t('adminCommerce.carrier') }}</th>
                  <th scope="col">{{ t('adminCommerce.trackingNumber') }}</th>
                  <th scope="col">{{ t('common.status') }}</th>
                  <th scope="col">{{ t('adminCommerce.product') }}</th>
                  <th scope="col">{{ t('adminCommerce.quantity') }}</th>
                  <th scope="col">{{ t('adminCommerce.shippedAt') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="shipment in shipments" :key="shipment.id">
                  <td>{{ shipment.shipmentNo }}</td>
                  <td>{{ shipment.carrier }}</td>
                  <td>{{ shipment.trackingNo }}</td>
                  <td><StatusTag :status="shipment.status" /></td>
                  <td>{{ shipment.lines.map((line) => line.productName).join(', ') }}</td>
                  <td>{{ shipment.lines.reduce((sum, line) => sum + line.quantity, 0) }}</td>
                  <td>{{ dateTime(shipment.shippedAt) }}</td>
                </tr>
              </tbody>
            </table>
          </DataTableShell>
        </AsyncStateView>
      </section>
    </AsyncStateView>
  </div>
</template>

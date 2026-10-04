<script setup lang="ts">
import { Refresh, Search, Upload } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import {
  auditTrace,
  auditTraceEventText,
  stats as fetchStats,
  type AuditTraceEvent,
} from '@/api/admin'
import { normalizeApiId, sameApiId, type ApiId } from '@/api/ids'
import {
  createCatalogSpu,
  listCatalogSpuPage,
  retireCatalogSpu,
  transitionCatalogSpuStatus,
  updateCatalogSpu,
  uploadImage,
} from '@/api/catalog'
import * as ordersApi from '@/api/orders'
import type { PageEnvelope } from '@/api/page'
import { adminPaymentForOrder, adminRefundPayment } from '@/api/payments'
import ProductImage from '@/components/ProductImage.vue'
import AdminPageToolbar from '@/components/admin/AdminPageToolbar.vue'
import MetricStrip, { type MetricItem } from '@/components/admin/MetricStrip.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import { useRouteQueryState, type RouteQuerySchema } from '@/composables/useRouteQueryState'
import type { CatalogSpu, Order, ProductStatus, Stats } from '@/types'
import {
  allowedCatalogStatusTargets,
  catalogAdminFormPayload,
  catalogToAdminForm,
  emptyCatalogAdminForm,
  type CatalogAdminForm,
} from '@/utils/catalogAdmin'
import { dateTime, money, orderStatusKey, orderStatusLabel, statusType } from '@/utils/format'

defineOptions({ name: 'AdminView' })

interface AdminQuery {
  order: string
  productKeyword: string
  productStatus: string
}

const adminQuerySchema: RouteQuerySchema<AdminQuery> = {
  parse(query: LocationQuery) {
    const raw = Array.isArray(query.order) ? query.order[0] : query.order
    const productKeyword = Array.isArray(query.productKeyword)
      ? query.productKeyword[0]
      : query.productKeyword
    const productStatus = Array.isArray(query.productStatus)
      ? query.productStatus[0]
      : query.productStatus
    return {
      order: String(raw ?? ''),
      productKeyword: String(productKeyword ?? ''),
      productStatus: String(productStatus ?? ''),
    }
  },
  serialize(value: AdminQuery): LocationQueryRaw {
    const query: LocationQueryRaw = {}
    if (value.order.trim()) query.order = value.order.trim()
    if (value.productKeyword.trim()) query.productKeyword = value.productKeyword.trim()
    if (value.productStatus.trim()) query.productStatus = value.productStatus.trim()
    return query
  },
}

const { t } = useI18n()
const notify = useNotify()
const { state: query } = useRouteQueryState(adminQuerySchema, { debounceMs: 250 })
const statsState = useAsyncState<Stats>({ preserveData: true })
const productsState = useAsyncState<PageEnvelope<CatalogSpu>>({ preserveData: true })
const ordersState = useAsyncState<PageEnvelope<Order>>({ preserveData: true })
const traceState = useAsyncState<AuditTraceEvent[]>({ preserveData: false })
const pendingKeys = ref(new Set<string>())
const productPageNumber = ref(0)
const orderPageNumber = ref(0)
const productPageSize = 20
const orderPageSize = 25
const productStatuses: ProductStatus[] = [
  'DRAFT',
  'PENDING_REVIEW',
  'APPROVED',
  'LISTED',
  'UNLISTED',
  'RECYCLED',
]
const productDialog = ref(false)
const uploadingProductImage = ref(false)
const productFormRef = ref<FormInstance>()
const traceKeyword = ref('')
const refundKeys = new Map<string, string>()
let productSnapshot = ''
let orderSearchTimer: ReturnType<typeof setTimeout> | null = null

const productForm = reactive<CatalogAdminForm>(emptyCatalogAdminForm())

const productRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('admin.nameRequired'), trigger: 'blur' }],
  title: [{ required: true, message: t('admin.titleRequired'), trigger: 'blur' }],
  categoryId: [{ required: true, message: t('admin.categoryRequired'), trigger: 'blur' }],
  shopId: [{ required: true, message: t('admin.shopRequired'), trigger: 'blur' }],
  originalPrice: [
    {
      validator: (_rule, value, callback) => {
        const numeric = Number(value)
        if (value === '' || !Number.isFinite(numeric) || numeric <= 0) {
          callback(new Error(t('admin.originalPriceRequired')))
        } else callback()
      },
      trigger: 'blur',
    },
  ],
  specificationsJson: [
    {
      validator: (_rule, value, callback) => {
        try {
          const dimensions = JSON.parse(String(value || '{}')) as Record<string, unknown>
          if (
            !dimensions ||
            Array.isArray(dimensions) ||
            typeof dimensions !== 'object' ||
            Object.keys(dimensions).length === 0
          ) {
            throw new Error()
          }
          callback()
        } catch {
          callback(new Error(t('admin.specificationsRequired')))
        }
      },
      trigger: 'blur',
    },
  ],
}))

const stats = computed(() => statsState.data.value)
const productPage = computed(() => productsState.data.value)
const orderPage = computed(() => ordersState.data.value)
const products = computed(() => productPage.value?.content ?? [])
const orders = computed(() => orderPage.value?.content ?? [])
const traceEvents = computed(() => traceState.data.value ?? [])
const metrics = computed<MetricItem[]>(() => [
  { key: 'gmv', label: t('common.gmv'), value: money(stats.value?.totalGmv), tone: 'success' },
  { key: 'orders', label: t('common.orders'), value: stats.value?.totalOrders ?? 0 },
  { key: 'visits', label: t('common.visits'), value: stats.value?.totalVisits ?? 0 },
  { key: 'returns', label: t('common.returnRate'), value: stats.value?.returnRate ?? '0%' },
])
const productDirty = computed(() => serializeProductForm() !== productSnapshot)
function isPending(key: string): boolean {
  return pendingKeys.value.has(key)
}

function setPending(key: string, value: boolean) {
  const next = new Set(pendingKeys.value)
  if (value) next.add(key)
  else next.delete(key)
  pendingKeys.value = next
}

function serializeProductForm(): string {
  return JSON.stringify(productForm)
}

async function loadStats() {
  await statsState.load(() => fetchStats(), { preserveData: true })
}

async function loadProducts(pageNumber = productPageNumber.value) {
  productPageNumber.value = pageNumber
  await productsState.load(
    ({ signal }) =>
      listCatalogSpuPage({
        page: pageNumber,
        size: productPageSize,
        keyword: query.productKeyword.trim() || undefined,
        status: (query.productStatus.trim() || undefined) as ProductStatus | undefined,
        signal,
      }),
    {
      preserveData: true,
      isEmpty: (page) => page.content.length === 0,
    },
  )
}

async function loadOrders(pageNumber = orderPageNumber.value) {
  orderPageNumber.value = pageNumber
  await ordersState.load(
    ({ signal }) =>
      ordersApi.allOrderPage({
        page: pageNumber,
        size: orderPageSize,
        keyword: query.order.trim() || undefined,
        signal,
      }),
    {
      preserveData: true,
      isEmpty: (page) => page.content.length === 0,
    },
  )
}

function changeProductPage(pageNumber: number) {
  void loadProducts(pageNumber - 1)
}

function changeOrderPage(pageNumber: number) {
  void loadOrders(pageNumber - 1)
}

function refreshAdmin() {
  void Promise.allSettled([loadStats(), loadProducts(), loadOrders()])
}

function openProductDialog(product?: CatalogSpu) {
  Object.assign(productForm, product ? catalogToAdminForm(product) : emptyCatalogAdminForm())
  productSnapshot = serializeProductForm()
  productDialog.value = true
  void nextTick(() => productFormRef.value?.clearValidate())
}

async function confirmDiscardProduct(): Promise<boolean> {
  if (!productDirty.value) return true
  return notify.confirm({
    title: t('admin.unsavedTitle'),
    content: t('admin.unsavedContent'),
    confirmText: t('common.ok'),
  })
}

async function beforeProductClose(done: () => void) {
  if (await confirmDiscardProduct()) done()
}

async function closeProductDialog() {
  if (await confirmDiscardProduct()) productDialog.value = false
}

async function uploadProductImage(event: Event) {
  if (uploadingProductImage.value) return
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  uploadingProductImage.value = true
  try {
    const uploaded = await uploadImage(file, 'product')
    productForm.imageUrl = uploaded.path
    notify.success(
      uploaded.cropped ? t('common.imageUploadedCropped') : t('common.imageUploaded'),
      { key: 'admin:product:image' },
    )
  } catch (error) {
    notify.fromApiError(error, 'common.unableToUploadImage')
  } finally {
    uploadingProductImage.value = false
    input.value = ''
  }
}

function patchProduct(product: CatalogSpu, created = false) {
  const page = productsState.data.value
  const rows = page?.content
  if (!rows) return
  const index = rows.findIndex((row) => sameApiId(row.id, product.id))
  if (index >= 0) rows.splice(index, 1, product)
  else {
    rows.unshift(product)
    if (rows.length > productPageSize) rows.pop()
    if (created && page) page.totalElements += 1
  }
}

async function saveProduct() {
  if (!(await productFormRef.value?.validate().catch(() => false))) return
  const key = `product:save:${productForm.id ?? 'new'}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    statsState.cancel()
    productsState.cancel()
    const created = !productForm.id
    const payload = catalogAdminFormPayload(productForm)
    const saved = productForm.id
      ? await updateCatalogSpu(productForm.id, payload)
      : await createCatalogSpu(payload)
    patchProduct(saved, created)
    productSnapshot = serializeProductForm()
    productDialog.value = false
    notify.success(t('admin.productSaved'), { key: 'admin:product:saved' })
  } catch (error) {
    notify.fromApiError(error, 'common.unableToSaveProduct')
  } finally {
    setPending(key, false)
  }
}

async function removeProduct(product: CatalogSpu) {
  const key = `product:delete:${product.id}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    const confirmed = await notify.confirm({
      content: t('common.deleteProductConfirm'),
      confirmText: t('common.ok'),
      type: 'warning',
    })
    if (!confirmed) return
    statsState.cancel()
    productsState.cancel()
    patchProduct(await retireCatalogSpu(product.id))
    notify.success(t('admin.productDeleted'), { key: 'admin:product:deleted' })
  } catch (error) {
    notify.fromApiError(error, 'common.unableToDeleteProduct')
  } finally {
    setPending(key, false)
  }
}

async function transitionProductStatus(product: CatalogSpu, targetStatus: ProductStatus) {
  const key = `product:status:${product.id}:${targetStatus}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    statsState.cancel()
    productsState.cancel()
    patchProduct(await transitionCatalogSpuStatus(product.id, targetStatus))
    notify.success(t('admin.productStatusChanged'), { key: `admin:product:status:${product.id}` })
  } catch (error) {
    notify.fromApiError(error, 'common.unableToSaveProduct')
  } finally {
    setPending(key, false)
  }
}

function patchOrder(order: Order) {
  const rows = ordersState.data.value?.content
  const index = rows?.findIndex((row) => sameApiId(row.id, order.id)) ?? -1
  if (rows && index >= 0) rows.splice(index, 1, order)
}

async function runOrderAction(action: string, order: Order, operation: () => Promise<Order>) {
  const key = `${action}:${order.id}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    statsState.cancel()
    ordersState.cancel()
    patchOrder(await operation())
    notify.success(t('admin.orderUpdated'), { key: `admin:order:${order.id}` })
  } catch (error) {
    notify.fromApiError(error, 'common.unableToUpdateOrder')
  } finally {
    setPending(key, false)
  }
}

async function refundAndConfirm(order: Order) {
  const key = `refund:${order.id}`
  if (isPending(key)) return
  setPending(key, true)
  try {
    const confirmed = await notify.confirm({
      content: t('common.refund'),
      confirmText: t('common.refund'),
      type: 'warning',
    })
    if (!confirmed) return

    statsState.cancel()
    ordersState.cancel()
    const payment = await adminPaymentForOrder(order.id)
    let refundCompleted = payment.status === 'REFUNDED'
    if (payment.paymentNo) {
      if (!refundCompleted) {
        const orderKey = normalizeApiId(order.id)
        const idempotencyKey = refundKeys.get(orderKey) ?? createRefundKey(order.id)
        refundKeys.set(orderKey, idempotencyKey)
        await adminRefundPayment(
          {
            paymentNo: payment.paymentNo,
            amount: payment.amount ?? order.price,
            reason: t('common.refund'),
          },
          idempotencyKey,
        )
        refundCompleted = true
      }
    } else {
      notify.warning(t('common.noPaymentToRefund'), { key: `admin:refund:none:${order.id}` })
    }

    try {
      patchOrder(await ordersApi.confirmReturn(order.id))
    } catch (error) {
      if (refundCompleted) {
        notify.warning(t('admin.refundCompleteReturnPending'), {
          key: `admin:refund:pending:${order.id}`,
        })
        return
      }
      throw error
    }
    refundKeys.delete(normalizeApiId(order.id))
    notify.success(t('admin.orderUpdated'), { key: `admin:order:${order.id}` })
  } catch (error) {
    notify.fromApiError(error, 'common.unableToUpdateOrder')
  } finally {
    setPending(key, false)
  }
}

function createRefundKey(orderId: ApiId): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `admin-refund:${orderId}:${crypto.randomUUID()}`
  }
  return `admin-refund:${orderId}:${Date.now().toString(36)}`
}

async function loadTrace() {
  const keyword = traceKeyword.value.trim()
  if (!keyword) {
    traceState.reset()
    return
  }
  await traceState.load(() => auditTrace(keyword), {
    preserveData: false,
    isEmpty: (events) => events.length === 0,
  })
}

function auditEventLabel(eventType: string): string {
  const normalized = eventType
    .replace(/[_:-]+/g, ' ')
    .trim()
    .toLocaleLowerCase()
  return normalized ? normalized.charAt(0).toLocaleUpperCase() + normalized.slice(1) : eventType
}

function productRowKey(product: CatalogSpu): string {
  return `catalog:${product.id}`
}

function orderRowKey(order: Order): string {
  return `order:${order.id}`
}

function productRowClassName({ row }: { row: CatalogSpu }): string {
  return `admin-data-row admin-data-row--catalog-${row.id}`
}

function orderRowClassName({ row }: { row: Order }): string {
  return `admin-data-row admin-data-row--order-${row.id}`
}

watch(
  () => query.order,
  () => {
    if (orderSearchTimer !== null) clearTimeout(orderSearchTimer)
    orderSearchTimer = setTimeout(() => {
      orderSearchTimer = null
      void loadOrders(0)
    }, 250)
  },
)

watch(
  () => [query.productKeyword, query.productStatus],
  () => {
    void loadProducts(0)
  },
)

onUnmounted(() => {
  if (orderSearchTimer !== null) clearTimeout(orderSearchTimer)
  statsState.cancel()
  productsState.cancel()
  ordersState.cancel()
  traceState.cancel()
})

refreshAdmin()
</script>

<template>
  <div
    class="route-view admin-page commerce-page"
    data-surface="operations-observatory"
    data-layout="catalog-fulfillment-audit"
  >
    <PageHeader
      :eyebrow="t('nav.admin')"
      :title="t('admin.title')"
      :description="t('admin.description')"
    >
      <template #actions>
        <el-button
          :icon="Refresh"
          :loading="
            statsState.isLoading.value ||
            productsState.isLoading.value ||
            ordersState.isLoading.value
          "
          @click="refreshAdmin"
        >
          {{ t('common.refresh') }}
        </el-button>
      </template>
    </PageHeader>

    <AsyncStateView
      :status="statsState.status.value"
      :error="statsState.error.value"
      :preserve-content-on-error="statsState.data.value !== null"
      @retry="loadStats"
    >
      <MetricStrip :items="metrics" />
    </AsyncStateView>

    <section
      class="admin-section commerce-section"
      data-workspace="catalog"
      :aria-labelledby="'catalog-title'"
    >
      <div class="section-heading commerce-section__heading">
        <div>
          <h2 id="catalog-title">{{ t('admin.catalog') }}</h2>
          <p>{{ t('admin.catalogDescription') }}</p>
        </div>
      </div>
      <p class="catalog-inventory-hint">{{ t('admin.catalogInventoryHint') }}</p>
      <AdminPageToolbar :aria-label="t('admin.catalog')">
        <template #search>
          <el-input
            v-model="query.productKeyword"
            clearable
            :aria-label="t('admin.catalogSearch')"
            :placeholder="t('admin.catalogSearch')"
          />
        </template>
        <template #filters>
          <el-select v-model="query.productStatus" clearable :aria-label="t('admin.statusFilter')">
            <el-option :label="t('admin.allProductStatuses')" value="" />
            <el-option v-for="status in productStatuses" :key="status" :label="status" :value="status" />
          </el-select>
        </template>
        <template #actions>
          <el-button type="primary" @click="openProductDialog()">
            {{ t('admin.createProduct') }}
          </el-button>
        </template>
      </AdminPageToolbar>
      <AsyncStateView
        :status="productsState.status.value"
        :error="productsState.error.value"
        :preserve-content-on-error="productsState.data.value !== null"
        @retry="loadProducts"
      >
        <DataTableShell
          class="product-table"
          :empty="products.length === 0"
          :aria-label="t('admin.catalog')"
        >
          <template #empty>{{ t('admin.noProducts') }}</template>
          <el-table
            class="admin-data-table"
            :data="products"
            row-key="id"
            :row-class-name="productRowClassName"
            size="small"
          >
            <el-table-column width="76">
              <template #default="{ row }">
                <ProductImage v-if="row.imageUrl" :src="row.imageUrl" :alt="row.name" />
                <span v-else class="product-image-placeholder" aria-hidden="true" />
              </template>
            </el-table-column>
            <el-table-column :label="t('common.name')" min-width="160">
              <template #default="{ row }">
                <span class="table-row-anchor" :data-row-key="productRowKey(row)">
                  {{ row.name }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="title" :label="t('admin.productTitle')" min-width="180" />
            <el-table-column :label="t('common.price')" width="130">
              <template #default="{ row }">{{ money(row.originalPrice) }}</template>
            </el-table-column>
            <el-table-column :label="t('common.status')" width="140">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('admin.skuCount')" width="100">
              <template #default="{ row }">{{ row.skus.length }}</template>
            </el-table-column>
            <el-table-column :label="t('common.action')" min-width="300" fixed="right">
              <template #default="{ row }">
                <el-button
                  size="small"
                  :aria-label="t('admin.editProductNamed', { name: row.name })"
                  @click="openProductDialog(row)"
                >
                  {{ t('common.edit') }}
                </el-button>
                <el-button
                  v-for="targetStatus in allowedCatalogStatusTargets(row.status)"
                  :key="`${row.id}-${targetStatus}`"
                  size="small"
                  :loading="isPending(`product:status:${row.id}:${targetStatus}`)"
                  @click="transitionProductStatus(row, targetStatus)"
                >
                  {{ t('admin.changeToStatus', { status: targetStatus }) }}
                </el-button>
                <el-button
                  size="small"
                  type="danger"
                  plain
                  :aria-label="t('admin.retireProductNamed', { name: row.name })"
                  :loading="isPending(`product:delete:${row.id}`)"
                  @click="removeProduct(row)"
                >
                  {{ t('admin.retireProduct') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
        <el-pagination
          v-if="(productPage?.totalElements ?? 0) > productPageSize"
          class="admin-product-pagination"
          background
          layout="prev, pager, next, total"
          :current-page="productPageNumber + 1"
          :page-size="productPageSize"
          :total="productPage?.totalElements ?? 0"
          @current-change="changeProductPage"
        />
      </AsyncStateView>
    </section>

    <section
      class="admin-section commerce-section"
      data-workspace="fulfillment"
      :aria-labelledby="'fulfillment-title'"
    >
      <div class="section-heading commerce-section__heading">
        <div>
          <h2 id="fulfillment-title">{{ t('admin.fulfillment') }}</h2>
          <p>{{ t('admin.fulfillmentDescription') }}</p>
        </div>
      </div>
      <AdminPageToolbar :aria-label="t('admin.fulfillment')">
        <template #search>
          <el-input
            v-model="query.order"
            clearable
            :aria-label="t('common.searchOrders')"
            :placeholder="t('common.searchOrders')"
          />
        </template>
      </AdminPageToolbar>
      <AsyncStateView
        :status="ordersState.status.value"
        :error="ordersState.error.value"
        :preserve-content-on-error="ordersState.data.value !== null"
        @retry="loadOrders"
      >
        <DataTableShell
          class="order-table"
          :empty="orders.length === 0"
          :aria-label="t('admin.fulfillment')"
        >
          <template #empty>{{ t('admin.noOrders') }}</template>
          <el-table
            class="admin-data-table"
            :data="orders"
            row-key="id"
            :row-class-name="orderRowClassName"
            size="small"
          >
            <el-table-column :label="t('common.order')" min-width="160">
              <template #default="{ row }">
                <span class="table-row-anchor" :data-row-key="orderRowKey(row)">
                  {{ row.orderNo }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="productName" :label="t('common.product')" min-width="150" />
            <el-table-column prop="buyerName" :label="t('common.buyer')" width="120" />
            <el-table-column :label="t('common.created')" min-width="170">
              <template #default="{ row }">{{ dateTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column :label="t('common.status')" width="140">
              <template #default="{ row }">
                <StatusTag
                  :status="orderStatusKey(row.status)"
                />
              </template>
            </el-table-column>
            <el-table-column :label="t('common.action')" width="180" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="['PAID', 'PARTIALLY_SHIPPED'].includes(orderStatusKey(row.status))"
                  size="small"
                  type="primary"
                  :loading="isPending(`ship:${row.id}`)"
                  @click="runOrderAction('ship', row, () => ordersApi.shipOrder(row.id))"
                >
                  {{ t('common.ship') }}
                </el-button>
                <el-button
                  v-if="orderStatusKey(row.status) === 'RETURN_REQUESTED'"
                  size="small"
                  :loading="isPending(`approve-return:${row.id}`)"
                  @click="
                    runOrderAction('approve-return', row, () => ordersApi.approveReturn(row.id))
                  "
                >
                  {{ t('common.approveReturn') }}
                </el-button>
                <el-button
                  v-if="orderStatusKey(row.status) === 'RETURN_SHIPPING'"
                  size="small"
                  :loading="isPending(`refund:${row.id}`)"
                  @click="refundAndConfirm(row)"
                >
                  {{ t('common.refund') }}
                </el-button>
                <el-button
                  v-if="orderStatusKey(row.status) === 'PARTIALLY_RECEIVED'"
                  size="small"
                  :loading="isPending(`receive:${row.id}`)"
                  @click="runOrderAction('receive', row, () => ordersApi.receiveOrder(row.id))"
                >
                  {{ t('common.receive') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
        <el-pagination
          v-if="(orderPage?.totalElements ?? 0) > orderPageSize"
          class="admin-order-pagination"
          background
          layout="prev, pager, next, total"
          :current-page="orderPageNumber + 1"
          :page-size="orderPageSize"
          :total="orderPage?.totalElements ?? 0"
          @current-change="changeOrderPage"
        />
      </AsyncStateView>
    </section>

    <section
      class="admin-section commerce-section"
      data-workspace="audit"
      :aria-labelledby="'audit-title'"
    >
      <div class="section-heading commerce-section__heading">
        <div>
          <h2 id="audit-title">{{ t('admin.audit') }}</h2>
          <p>{{ t('admin.auditDescription') }}</p>
        </div>
      </div>
      <AdminPageToolbar :aria-label="t('admin.audit')">
        <template #search>
          <el-input
            v-model="traceKeyword"
            clearable
            :aria-label="t('admin.traceId')"
            :placeholder="t('common.traceIdPlaceholder')"
            @keyup.enter="loadTrace"
          />
        </template>
        <template #actions>
          <el-button
            type="primary"
            :icon="Search"
            :loading="traceState.isLoading.value"
            :aria-label="t('admin.searchTrace')"
            @click="loadTrace"
          >
            {{ t('common.search') }}
          </el-button>
        </template>
      </AdminPageToolbar>
      <AsyncStateView
        :status="traceState.status.value"
        :error="traceState.error.value"
        :empty-title="t('admin.noAuditEvents')"
        @retry="loadTrace"
      >
        <template #idle
          ><p class="trace-empty">{{ t('common.noTraceData') }}</p></template
        >
        <el-timeline class="trace-timeline">
          <el-timeline-item
            v-for="event in traceEvents"
            :key="event.id"
            :timestamp="event.createdAt"
            placement="top"
          >
            <h3>{{ auditEventLabel(event.eventType) }}</h3>
            <p :aria-label="auditTraceEventText(event)">{{ event.detail }}</p>
            <p v-if="event.actorUserId || event.traceId" class="trace-event-meta">
              <code v-if="event.actorUserId">user {{ event.actorUserId }}</code>
              <code v-if="event.traceId">trace {{ event.traceId }}</code>
            </p>
          </el-timeline-item>
        </el-timeline>
      </AsyncStateView>
    </section>

    <el-dialog
      v-model="productDialog"
      class="admin-product-dialog"
      :title="productForm.id ? t('admin.editProduct') : t('admin.createProduct')"
      width="min(680px, 94vw)"
      :before-close="beforeProductClose"
    >
      <el-form ref="productFormRef" :model="productForm" :rules="productRules" label-position="top">
        <div class="product-form-grid">
          <el-form-item :label="t('common.name')" prop="name"
            ><el-input v-model="productForm.name"
          /></el-form-item>
          <el-form-item :label="t('admin.productTitle')" prop="title"
            ><el-input v-model="productForm.title"
          /></el-form-item>
          <el-form-item :label="t('admin.categoryId')" prop="categoryId"
            ><el-input v-model="productForm.categoryId" inputmode="numeric"
          /></el-form-item>
          <el-form-item :label="t('admin.shopId')" prop="shopId"
            ><el-input v-model="productForm.shopId" inputmode="numeric"
          /></el-form-item>
          <el-form-item :label="t('admin.originalPrice')" prop="originalPrice"
            ><el-input v-model="productForm.originalPrice" type="number"
          /></el-form-item>
          <el-form-item :label="t('admin.memberPrice')"
            ><el-input v-model="productForm.memberPrice" type="number"
          /></el-form-item>
          <el-form-item :label="t('admin.strikePrice')"
            ><el-input v-model="productForm.strikePrice" type="number"
          /></el-form-item>
        </div>
        <el-form-item :label="t('admin.specifications')" prop="specificationsJson"
          ><el-input
            v-model="productForm.specificationsJson"
            type="textarea"
            :rows="4"
            :placeholder="'{&quot;color&quot;: [&quot;Black&quot;, &quot;White&quot;]}'"
          />
          <small class="form-help">{{ t('admin.specificationsHint') }}</small>
        </el-form-item>
        <el-form-item :label="t('admin.attributes')"
          ><el-input v-model="productForm.attributesJson" type="textarea" :rows="3"
        /></el-form-item>
        <el-form-item :label="t('admin.regionPrices')"
          ><el-input v-model="productForm.regionPricesJson" type="textarea" :rows="2"
        /></el-form-item>
        <el-form-item :label="t('admin.detailJsonLd')"
          ><el-input v-model="productForm.detailJsonLd" type="textarea" :rows="2"
        /></el-form-item>
        <el-form-item :label="t('admin.supplierPrivateRemark')"
          ><el-input v-model="productForm.supplierPrivateRemark" type="textarea" :rows="2"
        /></el-form-item>
        <el-form-item :label="t('common.image')">
          <div class="product-image-editor">
            <label class="file-picker" for="product-image-input">
              <el-icon aria-hidden="true"><Upload /></el-icon>
              <span>{{ t('common.upload') }}</span>
              <input
                id="product-image-input"
                type="file"
                accept="image/png,image/jpeg,image/webp"
                :disabled="uploadingProductImage"
                @change="uploadProductImage"
              />
            </label>
            <ProductImage
              v-if="productForm.imageUrl"
              :src="productForm.imageUrl"
              :alt="productForm.name || t('common.product')"
            />
          </div>
        </el-form-item>
        <p class="form-help">{{ t('admin.catalogInventoryHint') }}</p>
      </el-form>
      <template #footer>
        <el-button @click="closeProductDialog">{{ t('common.cancel') }}</el-button>
        <el-button
          type="primary"
          :loading="isPending(`product:save:${productForm.id ?? 'new'}`)"
          @click="saveProduct"
        >
          {{ t('common.save') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-page {
  display: grid;
  gap: var(--space-6);
  width: 100%;
  min-width: 0;
  max-width: 100%;
  container-type: inline-size;
}

.admin-section {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
  padding-block: var(--space-5);
  border-block: 1px solid var(--admin-line);
  background: transparent;
}

.admin-section + .admin-section {
  border-top: 0;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.section-heading h2,
.section-heading p,
.trace-empty,
.trace-timeline h3,
.trace-timeline p {
  margin: 0;
}

.section-heading h2 {
  color: var(--admin-ink);
  font-size: var(--text-lg);
  line-height: var(--leading-tight);
}

.section-heading p,
.trace-empty,
.trace-timeline p,
.trace-timeline code {
  color: var(--admin-muted);
  font-size: var(--text-sm);
}

.section-heading p {
  margin-top: var(--space-1);
}

.catalog-inventory-hint,
.form-help {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.catalog-inventory-hint {
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-control);
  background: var(--color-surface-subtle);
}

.form-help {
  display: block;
  margin-top: var(--space-1);
}

.product-image-placeholder {
  display: block;
  width: 48px;
  aspect-ratio: 1;
  border-radius: var(--radius-control);
  background: var(--admin-surface-subtle);
}

.admin-data-table {
  min-width: 760px;
  color: var(--admin-ink);
  font-variant-numeric: tabular-nums;
}

.admin-data-table :deep(.el-table__header-wrapper th.el-table__cell) {
  background: var(--admin-surface-subtle);
  color: var(--admin-ink);
  font-size: var(--text-xs);
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.02em;
}

.admin-data-table :deep(.el-table__body tr) {
  transition: background-color var(--motion-fast);
}

.admin-data-table :deep(.el-table__body tr:hover > td.el-table__cell),
.admin-data-table :deep(.el-table__body tr:focus-within > td.el-table__cell) {
  background: var(--admin-primary-soft);
}

.table-row-anchor {
  display: inline-flex;
  min-width: 0;
  color: var(--admin-ink);
  font-weight: var(--font-weight-semibold);
  overflow-wrap: anywhere;
}

.product-table :deep(.el-table),
.order-table :deep(.el-table) {
  width: max-content;
  min-width: 100%;
}

.trace-empty {
  padding: var(--space-6) 0;
}

.trace-timeline h3 {
  color: var(--admin-ink);
  font-size: var(--text-sm);
}

.trace-event-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.trace-timeline code {
  font-family: var(--font-mono);
}

.product-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--space-4);
}

.product-image-editor {
  display: flex;
  align-items: flex-start;
  gap: var(--space-4);
}

.product-image-editor :deep(.product-image) {
  width: 96px;
  aspect-ratio: 1;
}

.admin-product-pagination,
.admin-order-pagination {
  justify-self: center;
  margin-top: var(--space-4);
}

@media (max-width: 760px) {
  .admin-page :deep(.admin-page-toolbar > *) {
    flex: 0 1 auto;
    width: 100%;
    max-width: none;
  }

  .admin-page :deep(.admin-page-toolbar__search),
  .admin-page :deep(.admin-page-toolbar__filters),
  .admin-page :deep(.admin-page-toolbar__actions) {
    width: 100%;
    max-width: none;
  }

  .section-heading,
  .product-image-editor {
    align-items: stretch;
    flex-direction: column;
  }

  .admin-section :deep(button),
  .admin-section :deep(input),
  .admin-section :deep(select),
  .admin-section :deep(textarea),
  .admin-section :deep(.el-input__wrapper),
  .admin-section :deep(.el-input-number),
  .admin-section :deep(.el-button) {
    min-height: var(--touch-target-min);
  }

  .admin-section :deep(.el-button) {
    white-space: normal;
  }

  .admin-page :deep(.admin-page-toolbar__actions) {
    justify-content: flex-start;
  }

  .product-form-grid {
    grid-template-columns: 1fr;
  }
}

:global(.admin-product-dialog)
  :is(button, input, select, textarea, .el-input__wrapper, .el-input-number, .el-button) {
  min-height: var(--touch-target-min);
}
</style>

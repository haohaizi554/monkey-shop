<script setup lang="ts">
import { Refresh, Search, Upload } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, nextTick, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import { auditTrace, stats as fetchStats, type AuditTraceEvent } from '@/api/admin'
import { addMonkey, deleteMonkey, listMonkeyPage, updateMonkey, uploadImage } from '@/api/catalog'
import * as ordersApi from '@/api/orders'
import type { PageEnvelope } from '@/api/page'
import { adminPaymentForOrder, adminRefundPayment } from '@/api/payments'
import ProductImage from '@/components/ProductImage.vue'
import AdminPageToolbar from '@/components/admin/AdminPageToolbar.vue'
import MetricStrip, { type MetricItem } from '@/components/admin/MetricStrip.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import { useRouteQueryState, type RouteQuerySchema } from '@/composables/useRouteQueryState'
import type { Monkey, MonkeyRequest, Order, Stats } from '@/types'
import { dateTime, money, orderStatusKey, orderStatusLabel, statusType } from '@/utils/format'

defineOptions({ name: 'AdminView' })

interface AdminQuery {
  order: string
}

const adminQuerySchema: RouteQuerySchema<AdminQuery> = {
  parse(query: LocationQuery) {
    const raw = Array.isArray(query.order) ? query.order[0] : query.order
    return { order: String(raw ?? '') }
  },
  serialize(value: AdminQuery): LocationQueryRaw {
    return value.order.trim() ? { order: value.order.trim() } : {}
  },
}

const { t } = useI18n()
const notify = useNotify()
const { state: query } = useRouteQueryState(adminQuerySchema, { debounceMs: 250 })
const statsState = useAsyncState<Stats>({ preserveData: true })
const productsState = useAsyncState<PageEnvelope<Monkey>>({ preserveData: true })
const ordersState = useAsyncState<PageEnvelope<Order>>({ preserveData: true })
const traceState = useAsyncState<AuditTraceEvent[]>({ preserveData: false })
const pendingKeys = ref(new Set<string>())
const productPageNumber = ref(0)
const orderPageNumber = ref(0)
const productPageSize = 20
const orderPageSize = 25
const productDialog = ref(false)
const uploadingProductImage = ref(false)
const productFormRef = ref<FormInstance>()
const traceKeyword = ref('')
const refundKeys = new Map<number, string>()
let productSnapshot = ''
let orderSearchTimer: ReturnType<typeof setTimeout> | null = null

const productForm = reactive<MonkeyRequest>({
  id: null,
  name: '',
  breed: '',
  price: '',
  description: '',
  imageUrl: '',
  stock: 0,
})

const productRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('admin.nameRequired'), trigger: 'blur' }],
  breed: [{ required: true, message: t('admin.breedRequired'), trigger: 'blur' }],
  price: [
    {
      validator: (_rule, value, callback) => {
        const numeric = Number(value)
        if (value === '' || !Number.isFinite(numeric) || numeric <= 0) {
          callback(new Error(t('admin.priceRequired')))
        } else callback()
      },
      trigger: 'blur',
    },
  ],
  stock: [
    {
      validator: (_rule, value, callback) => {
        const numeric = Number(value)
        if (!Number.isFinite(numeric) || numeric < 0) callback(new Error(t('admin.stockRequired')))
        else callback()
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
  return JSON.stringify({
    id: productForm.id ?? null,
    name: productForm.name.trim(),
    breed: productForm.breed.trim(),
    price: String(productForm.price),
    description: productForm.description?.trim() ?? '',
    imageUrl: productForm.imageUrl,
    stock: Number(productForm.stock),
  })
}

async function loadStats() {
  await statsState.load(() => fetchStats(), { preserveData: true })
}

async function loadProducts(pageNumber = productPageNumber.value) {
  productPageNumber.value = pageNumber
  await productsState.load(
    ({ signal }) => listMonkeyPage({ page: pageNumber, size: productPageSize, signal }),
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

function openProductDialog(monkey?: Monkey) {
  Object.assign(productForm, {
    id: monkey?.id ?? null,
    name: monkey?.name ?? '',
    breed: monkey?.breed ?? '',
    price: monkey?.price ?? '',
    description: monkey?.description ?? '',
    imageUrl: monkey?.imageUrl ?? '',
    stock: monkey?.stock ?? 0,
  })
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

function patchProduct(product: Monkey, created = false) {
  const page = productsState.data.value
  const rows = page?.content
  if (!rows) return
  const index = rows.findIndex((row) => row.id === product.id)
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
    const saved = productForm.id
      ? await updateMonkey({ ...productForm })
      : await addMonkey({ ...productForm })
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

async function removeProduct(product: Monkey) {
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
    await deleteMonkey(product.id)
    const page = productsState.data.value
    const rows = page?.content
    const index = rows?.findIndex((row) => row.id === product.id) ?? -1
    if (rows && index >= 0) {
      rows.splice(index, 1)
      if (page) page.totalElements = Math.max(0, page.totalElements - 1)
      if (rows.length === 0 && productPageNumber.value > 0) {
        void loadProducts(productPageNumber.value - 1)
      }
    }
    notify.success(t('admin.productDeleted'), { key: 'admin:product:deleted' })
  } catch (error) {
    notify.fromApiError(error, 'common.unableToDeleteProduct')
  } finally {
    setPending(key, false)
  }
}

function patchOrder(order: Order) {
  const rows = ordersState.data.value?.content
  const index = rows?.findIndex((row) => row.id === order.id) ?? -1
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
        const idempotencyKey = refundKeys.get(order.id) ?? createRefundKey(order.id)
        refundKeys.set(order.id, idempotencyKey)
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
    refundKeys.delete(order.id)
    notify.success(t('admin.orderUpdated'), { key: `admin:order:${order.id}` })
  } catch (error) {
    notify.fromApiError(error, 'common.unableToUpdateOrder')
  } finally {
    setPending(key, false)
  }
}

function createRefundKey(orderId: number): string {
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
  <div class="route-view admin-page">
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

    <section class="admin-section" :aria-labelledby="'catalog-title'">
      <div class="section-heading">
        <div>
          <h2 id="catalog-title">{{ t('admin.catalog') }}</h2>
          <p>{{ t('admin.catalogDescription') }}</p>
        </div>
        <el-button type="primary" @click="openProductDialog()">{{
          t('admin.createProduct')
        }}</el-button>
      </div>
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
          <el-table :data="products" row-key="id" size="small">
            <el-table-column width="76">
              <template #default="{ row }">
                <ProductImage v-if="row.imageUrl" :src="row.imageUrl" :alt="row.name" />
                <span v-else class="product-image-placeholder" aria-hidden="true" />
              </template>
            </el-table-column>
            <el-table-column prop="name" :label="t('common.name')" min-width="160" />
            <el-table-column prop="breed" :label="t('common.breed')" min-width="130" />
            <el-table-column :label="t('common.price')" width="130">
              <template #default="{ row }">{{ money(row.price) }}</template>
            </el-table-column>
            <el-table-column prop="stock" :label="t('common.stock')" width="100" />
            <el-table-column :label="t('common.action')" width="190" fixed="right">
              <template #default="{ row }">
                <el-button
                  size="small"
                  :aria-label="t('admin.editProductNamed', { name: row.name })"
                  @click="openProductDialog(row)"
                >
                  {{ t('common.edit') }}
                </el-button>
                <el-button
                  size="small"
                  type="danger"
                  plain
                  :aria-label="t('admin.deleteProductNamed', { name: row.name })"
                  :loading="isPending(`product:delete:${row.id}`)"
                  @click="removeProduct(row)"
                >
                  {{ t('common.delete') }}
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

    <section class="admin-section" :aria-labelledby="'fulfillment-title'">
      <div class="section-heading">
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
          <el-table :data="orders" row-key="id" size="small">
            <el-table-column prop="orderNo" :label="t('common.order')" min-width="160" />
            <el-table-column prop="productName" :label="t('common.product')" min-width="150" />
            <el-table-column prop="buyerName" :label="t('common.buyer')" width="120" />
            <el-table-column :label="t('common.created')" min-width="170">
              <template #default="{ row }">{{ dateTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column :label="t('common.status')" width="140">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" effect="plain">{{
                  orderStatusLabel(row.status)
                }}</el-tag>
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

    <section class="admin-section" :aria-labelledby="'audit-title'">
      <div class="section-heading">
        <div>
          <h2 id="audit-title">{{ t('admin.audit') }}</h2>
          <p>{{ t('admin.auditDescription') }}</p>
        </div>
      </div>
      <div class="trace-toolbar">
        <el-input
          v-model="traceKeyword"
          clearable
          :aria-label="t('admin.traceId')"
          :placeholder="t('common.traceIdPlaceholder')"
          @keyup.enter="loadTrace"
        />
        <el-button
          type="primary"
          :icon="Search"
          :loading="traceState.isLoading.value"
          :aria-label="t('admin.searchTrace')"
          @click="loadTrace"
        >
          {{ t('common.search') }}
        </el-button>
      </div>
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
            <p>{{ event.description }}</p>
            <p v-if="event.userId || event.traceId" class="trace-event-meta">
              <code v-if="event.userId">user {{ event.userId }}</code>
              <code v-if="event.traceId">trace {{ event.traceId }}</code>
            </p>
          </el-timeline-item>
        </el-timeline>
      </AsyncStateView>
    </section>

    <el-dialog
      v-model="productDialog"
      :title="productForm.id ? t('admin.editProduct') : t('admin.createProduct')"
      width="min(680px, 94vw)"
      :before-close="beforeProductClose"
    >
      <el-form ref="productFormRef" :model="productForm" :rules="productRules" label-position="top">
        <div class="product-form-grid">
          <el-form-item :label="t('common.name')" prop="name"
            ><el-input v-model="productForm.name"
          /></el-form-item>
          <el-form-item :label="t('common.breed')" prop="breed"
            ><el-input v-model="productForm.breed"
          /></el-form-item>
          <el-form-item :label="t('common.price')" prop="price"
            ><el-input v-model="productForm.price" type="number"
          /></el-form-item>
          <el-form-item :label="t('common.stock')" prop="stock"
            ><el-input v-model.number="productForm.stock" type="number"
          /></el-form-item>
        </div>
        <el-form-item :label="t('common.description')"
          ><el-input v-model="productForm.description" type="textarea" :rows="3"
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
.admin-page,
.admin-section {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
}

.admin-page {
  gap: var(--space-6);
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
  font-size: var(--text-lg);
}

.section-heading p,
.trace-empty,
.trace-timeline p,
.trace-timeline code {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.section-heading p {
  margin-top: var(--space-1);
}

.product-image-placeholder {
  display: block;
  width: 48px;
  aspect-ratio: 1;
  border-radius: var(--radius-control);
  background: var(--color-surface-subtle);
}

.trace-toolbar {
  display: flex;
  gap: var(--space-3);
  max-width: 680px;
}

.trace-toolbar :deep(.el-input) {
  flex: 1;
}

.trace-empty {
  padding: var(--space-6) 0;
}

.trace-timeline h3 {
  font-size: var(--text-sm);
}

.trace-event-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.trace-timeline code {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
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

@media (max-width: 600px) {
  .section-heading,
  .trace-toolbar,
  .product-image-editor {
    align-items: stretch;
    flex-direction: column;
  }

  .section-heading :deep(.el-button),
  .trace-toolbar :deep(.el-button) {
    width: 100%;
  }

  .product-form-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<script setup lang="ts">
import { Refresh, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { currentTrackingProfile, trackingDashboard, trackingProductProfile } from '@/api/tracking'
import MetricStrip, { type MetricItem } from '@/components/admin/MetricStrip.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { usePageVisibility } from '@/composables/usePageVisibility'
import type { ProductProfile, RealtimeDashboard, TrackingEventType, UserProfileTag } from '@/types'
import { money } from '@/utils/format'

defineOptions({ name: 'DashboardView' })

const { locale, t } = useI18n()
const dashboardState = useAsyncState<RealtimeDashboard>()
const profileState = useAsyncState<UserProfileTag>()
const productState = useAsyncState<ProductProfile>()
const visibility = usePageVisibility()
const polling = ref(true)
const productId = ref<number | null>(1)
const productInputError = ref(false)
const loadedProductId = ref<number>()
const dashboardLastSuccessAt = ref<Date>()
const profileLastSuccessAt = ref<Date>()
const productLastSuccessAt = ref<Date>()
let dashboardRefreshRequested = false
let dashboardRefreshLoop: Promise<void> | undefined

const dashboard = computed(() => dashboardState.data.value)
const myProfile = computed(() => profileState.data.value)
const productProfile = computed(() => productState.data.value)
const funnel = computed(() => dashboard.value?.funnel ?? [])
const metrics = computed<MetricItem[]>(() => [
  {
    key: 'page-views',
    label: t('dashboard.pageViews'),
    value: dashboard.value?.pageViews ?? 0,
    tone: 'info',
  },
  {
    key: 'unique-visitors',
    label: t('dashboard.uniqueVisitors'),
    value: dashboard.value?.uniqueVisitors ?? 0,
  },
  {
    key: 'orders',
    label: t('dashboard.orders'),
    value: dashboard.value?.orderCount ?? 0,
    tone: 'success',
  },
  {
    key: 'payment-amount',
    label: t('dashboard.paymentAmount'),
    value: money(dashboard.value?.paymentAmount),
    tone: 'success',
  },
])

const eventLabels = computed<Record<TrackingEventType, string>>(() => ({
  PAGE_VIEW: t('dashboard.eventPageView'),
  CLICK: t('dashboard.eventClick'),
  SEARCH: t('dashboard.eventSearch'),
  PRODUCT_VIEW: t('dashboard.eventProductView'),
  ADD_TO_CART: t('dashboard.eventAddToCart'),
  ORDER_CREATED: t('dashboard.eventOrderCreated'),
  PAYMENT_SUCCESS: t('dashboard.eventPaymentSuccess'),
  UI_ERROR: t('dashboard.eventUiError'),
}))

function eventLabel(eventType: string): string {
  return eventLabels.value[eventType as TrackingEventType] ?? t('common.unknown')
}

function formatSuccessfulRefresh(value?: Date): string {
  if (!value) return '-'
  return new Intl.DateTimeFormat(locale.value, {
    dateStyle: 'short',
    timeStyle: 'medium',
  }).format(value)
}

function sourceLabel(value: string): string {
  return value === 'web' ? t('dashboard.webSource') : t('common.unknown')
}

function pageLabel(value: string): string {
  if (value.includes('INTERNAL_PAGE_TOKEN') || value.startsWith('/internal/')) return t('common.unknown')
  if (value === '/dashboard') return t('dashboard.pageDashboard')
  if (value === '/shop') return t('dashboard.pageShop')
  if (value === '/search') return t('dashboard.pageSearch')
  if (value === '/orders') return t('dashboard.pageOrders')
  if (value === '/cart') return t('dashboard.pageCart')
  if (value === '/checkout') return t('dashboard.pageCheckout')
  if (value === '/profile') return t('dashboard.pageProfile')
  if (value === '/membership') return t('dashboard.pageMembership')
  if (value === '/recommendations') return t('dashboard.pageRecommendations')
  if (/^\/shop\/[^/]+$/.test(value)) return t('dashboard.pageProduct')
  return t('common.unknown')
}

function normalizeProfileText(text?: string): string {
  if (!text) {
    return t('dashboard.noProfileSignal')
  }
  const labels = text
    .split(',')
    .map((part) => {
      const [key, ...values] = part.split('=')
      const value = values.join('=').trim()
      if (key === 'last') return `${t('dashboard.latestEvent')}: ${eventLabel(value)}`
      if (key === 'previous') return `${t('dashboard.previousEvent')}: ${eventLabel(value)}`
      if (key === 'page') return `${t('dashboard.page')}: ${pageLabel(value)}`
      if (key === 'source') return `${t('dashboard.source')}: ${sourceLabel(value)}`
      return t('common.unknown')
    })
    .filter(Boolean)

  return labels.length > 0 ? labels.join(' \u00b7 ') : t('common.unknown')
}

function tagLabel(tag: string): string {
  const [prefix, rawValue = ''] = tag.split(':', 2)
  if (prefix === 'event') {
    return `${t('dashboard.latestEvent')}: ${eventLabel(rawValue.toUpperCase())}`
  }
  if (prefix === 'page') {
    return `${t('dashboard.page')}: ${pageLabel(rawValue)}`
  }
  if (prefix === 'source') {
    return `${t('dashboard.source')}: ${sourceLabel(rawValue)}`
  }
  return tag === 'popular' ? t('dashboard.tagPopular') : t('common.unknown')
}

function productIdValue(value: unknown): number | null {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function loadDashboard(): Promise<void> {
  dashboardRefreshRequested = true
  if (!dashboardRefreshLoop) {
    dashboardRefreshLoop = runDashboardRefreshLoop()
  }
  return dashboardRefreshLoop
}

async function runDashboardRefreshLoop(): Promise<void> {
  try {
    while (dashboardRefreshRequested) {
      dashboardRefreshRequested = false
      const result = await dashboardState.load(() => trackingDashboard(5), { preserveData: true })
      if (result) dashboardLastSuccessAt.value = new Date()
    }
  } finally {
    dashboardRefreshLoop = undefined
  }
}

async function loadProfile() {
  const result = await profileState.load(() => currentTrackingProfile(), { preserveData: true })
  if (result) profileLastSuccessAt.value = new Date()
}

async function loadProductProfile() {
  const requestedProductId = productIdValue(productId.value)
  if (requestedProductId === null) {
    productInputError.value = true
    loadedProductId.value = undefined
    productLastSuccessAt.value = undefined
    productState.reset()
    return
  }

  productInputError.value = false
  const isCurrentProduct = loadedProductId.value === requestedProductId
  if (!isCurrentProduct) productLastSuccessAt.value = undefined
  const result = await productState.load(() => trackingProductProfile(requestedProductId), {
    preserveData: isCurrentProduct,
  })
  if (result && requestedProductId === productIdValue(productId.value)) {
    loadedProductId.value = requestedProductId
    productLastSuccessAt.value = new Date()
  }
}

function handleProductIdChange(value: number | undefined) {
  productId.value = value ?? null
  productInputError.value = productIdValue(productId.value) === null
  if (productInputError.value) {
    loadedProductId.value = undefined
    productLastSuccessAt.value = undefined
    productState.reset()
  }
}

function togglePolling() {
  polling.value = !polling.value
  if (!polling.value) {
    visibility.stop()
    return
  }

  visibility.start(loadDashboard, 5000)
  if (visibility.isVisible.value) {
    void loadDashboard()
  }
}

onMounted(() => {
  void loadProfile()
  void loadProductProfile()
  visibility.start(loadDashboard, 5000)
  if (visibility.isVisible.value) {
    void loadDashboard()
  }
})
</script>

<template>
  <div class="route-view dashboard-view">
    <PageHeader
      :eyebrow="t('dashboard.dataCenter')"
      :title="t('dashboard.title')"
      :description="t('dashboard.description')"
    >
      <template #actions>
        <el-button
          :icon="polling ? VideoPause : VideoPlay"
          :aria-pressed="polling"
          @click="togglePolling"
        >
          {{ polling ? t('dashboard.pausePolling') : t('dashboard.resumePolling') }}
        </el-button>
        <el-button
          :icon="Refresh"
          :loading="dashboardState.isLoading.value"
          :aria-label="t('dashboard.refreshNow')"
          @click="loadDashboard"
        >
          {{ t('dashboard.refreshNow') }}
        </el-button>
      </template>
    </PageHeader>

    <AsyncStateView
      :status="dashboardState.status.value"
      :error="dashboardState.error.value"
      :preserve-content-on-error="Boolean(dashboard)"
      @retry="loadDashboard"
    >
      <p
        v-if="dashboardLastSuccessAt"
        class="data-freshness" data-testid="dashboard-last-success"
        :class="{ 'is-stale': dashboardState.status.value === 'error' }"
      >
        {{
          t('dashboard.lastUpdated', {
            time: formatSuccessfulRefresh(dashboardLastSuccessAt),
          })
        }}
      </p>
      <MetricStrip :items="metrics" />

      <section class="dashboard-section" :aria-labelledby="'dashboard-funnel-title'">
        <div class="section-heading">
          <div>
            <h2 id="dashboard-funnel-title">{{ t('dashboard.conversionFunnel') }}</h2>
            <p>{{ t('dashboard.fromSearchToPayment') }}</p>
          </div>
        </div>
        <DataTableShell
          :aria-label="t('dashboard.conversionFunnel')"
          :empty="funnel.length === 0"
          :busy="dashboardState.status.value === 'updating'"
        >
          <template #empty>{{ t('common.noData') }}</template>
          <el-table :data="funnel" size="small">
            <el-table-column :label="t('dashboard.step')" min-width="180">
              <template #default="{ row }">{{ eventLabel(row.eventType) }}</template>
            </el-table-column>
            <el-table-column prop="count" :label="t('dashboard.count')" width="120" />
            <el-table-column :label="t('dashboard.conversionRate')" width="160">
              <template #default="{ row }">
                {{ (Number(row.conversionRate) * 100).toFixed(2) }}%
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </section>
    </AsyncStateView>

    <div class="profile-grid">
      <section class="dashboard-section profile-section" :aria-labelledby="'user-profile-title'">
        <div class="section-heading">
          <h2 id="user-profile-title">{{ t('dashboard.userProfile') }}</h2>
          <el-button
            text
            :icon="Refresh"
            :loading="profileState.isLoading.value"
            :aria-label="t('dashboard.refreshNow')"
            @click="loadProfile"
          />
        </div>
        <time
          v-if="profileLastSuccessAt"
          class="data-freshness"
          :class="{ 'is-stale': profileState.status.value === 'error' }"
          :datetime="profileLastSuccessAt.toISOString()"
          data-testid="user-profile-last-success"
        >
          {{
            t('dashboard.lastUpdated', {
              time: formatSuccessfulRefresh(profileLastSuccessAt),
            })
          }}
        </time>
        <AsyncStateView
          :status="profileState.status.value"
          :error="profileState.error.value"
          :preserve-content-on-error="Boolean(myProfile)"
          @retry="loadProfile"
        >
          <p class="profile-summary">{{ normalizeProfileText(myProfile?.profileSummary) }}</p>
          <div class="tag-row">
            <el-tag v-for="tag in myProfile?.behaviorTags ?? []" :key="tag" type="info">
              {{ tagLabel(tag) }}
            </el-tag>
            <el-tag v-for="tag in myProfile?.interestTags ?? []" :key="tag" type="success">
              {{ tagLabel(tag) }}
            </el-tag>
          </div>
        </AsyncStateView>
      </section>

      <section class="dashboard-section profile-section" :aria-labelledby="'product-profile-title'">
        <div class="section-heading">
          <h2 id="product-profile-title">{{ t('dashboard.productProfile') }}</h2>
        </div>
        <div class="product-profile-control">
          <span>{{ t('dashboard.productId') }}</span>
          <el-input-number
            v-model="productId"
            :step="1"
            size="small"
            :aria-label="t('dashboard.productId')"
            @change="handleProductIdChange"
          />
          <el-button
            text
            :icon="Refresh"
            :loading="productState.isLoading.value"
            :aria-label="t('dashboard.refreshNow')"
            @click="loadProductProfile"
          />
        </div>
        <p v-if="productInputError" class="product-input-error" role="alert">
          {{ t('dashboard.productIdRequired') }}
        </p>
        <time
          v-if="productLastSuccessAt"
          class="data-freshness"
          :class="{ 'is-stale': productState.status.value === 'error' }"
          :datetime="productLastSuccessAt.toISOString()"
          data-testid="product-profile-last-success"
        >
          {{
            t('dashboard.lastUpdated', {
              time: formatSuccessfulRefresh(productLastSuccessAt),
            })
          }}
        </time>
        <AsyncStateView
          :status="productState.status.value"
          :error="productState.error.value"
          :preserve-content-on-error="Boolean(productProfile)"
          @retry="loadProductProfile"
        >
          <p class="profile-summary">
            {{
              t('dashboard.salesAndScore', {
                sales: productProfile?.salesCount ?? 0,
                score: productProfile?.reviewScore ?? 0,
              })
            }}
          </p>
          <div class="tag-row">
            <el-tag v-for="tag in productProfile?.tagVector ?? []" :key="tag">
              {{ tagLabel(tag) }}
            </el-tag>
          </div>
        </AsyncStateView>
      </section>
    </div>
  </div>
</template>

<style scoped>
.dashboard-view {
  display: grid;
  gap: var(--space-5);
  min-width: 0;
}

.dashboard-section {
  display: grid;
  gap: var(--space-3);
  min-width: 0;
}

.data-freshness {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-2);
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  text-align: right;
}

.data-freshness.is-stale {
  color: var(--color-danger);
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.section-heading h2,
.section-heading p,
.profile-summary {
  margin: 0;
}

.section-heading h2 {
  font-size: var(--text-lg);
}

.section-heading p,
.section-heading time,
.product-profile-control span,
.profile-summary {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-5);
}

.profile-section {
  align-content: start;
  padding-block: var(--space-4);
  border-top: 1px solid var(--color-line);
}

.profile-summary {
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.product-profile-control {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.product-input-error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  min-width: 0;
  margin-top: var(--space-3);
}

.tag-row :deep(.el-tag) {
  max-width: 100%;
  height: auto;
  min-height: var(--control-height);
  white-space: normal;
}

@media (max-width: 900px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 600px) {
  .section-heading,
  .product-profile-control {
    align-items: stretch;
    flex-direction: column;
  }

  .product-profile-control :deep(.el-input-number) {
    width: 100%;
  }
}
</style>

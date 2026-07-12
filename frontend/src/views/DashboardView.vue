<script setup lang="ts">
import { Refresh, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { currentTrackingProfile, trackingDashboard, trackingProductProfile } from '@/api/tracking'
import MetricStrip, { type MetricItem } from '@/components/admin/MetricStrip.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState, type AsyncState, type AsyncStatus } from '@/composables/useAsyncState'
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
const productId = ref(1)

const dashboard = computed(() => dashboardState.data.value)
const myProfile = computed(() => profileState.data.value)
const productProfile = computed(() => productState.data.value)
const funnel = computed(() => dashboard.value?.funnel ?? [])
const generatedAt = computed(() => {
  if (!dashboard.value?.generatedAt) {
    return '-'
  }
  return new Intl.DateTimeFormat(locale.value, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(dashboard.value.generatedAt))
})
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

function displayStatus<T>(state: AsyncState<T>): AsyncStatus {
  if (state.data.value !== null && state.status.value === 'error') {
    return 'success'
  }
  return state.status.value
}

const dashboardDisplayStatus = computed(() => displayStatus(dashboardState))
const profileDisplayStatus = computed(() => displayStatus(profileState))
const productDisplayStatus = computed(() => displayStatus(productState))

function eventLabel(eventType: TrackingEventType): string {
  return eventLabels.value[eventType] ?? humanize(eventType)
}

function humanize(value: string): string {
  const normalized = value.replace(/[_:-]+/g, ' ').trim().toLowerCase()
  return normalized ? normalized.charAt(0).toUpperCase() + normalized.slice(1) : value
}

function normalizeProfileText(text?: string): string {
  if (!text) {
    return t('dashboard.noProfileSignal')
  }
  let normalized = text
  for (const eventType of Object.keys(eventLabels.value) as TrackingEventType[]) {
    normalized = normalized.replaceAll(eventType, eventLabel(eventType))
  }
  return normalized
    .replaceAll('last=', `${t('dashboard.latestEvent')}: `)
    .replaceAll('previous=', `${t('dashboard.previousEvent')}: `)
    .replaceAll('page=', `${t('dashboard.page')}: `)
    .replaceAll('source=web', `${t('dashboard.source')}: ${t('dashboard.webSource')}`)
    .replaceAll('source:web', `${t('dashboard.source')}: ${t('dashboard.webSource')}`)
    .replaceAll(',', ' · ')
}

function tagLabel(tag: string): string {
  const [prefix, rawValue = ''] = tag.split(':', 2)
  if (prefix === 'event') {
    const eventType = rawValue.toUpperCase() as TrackingEventType
    return `${t('dashboard.latestEvent')}: ${eventLabels.value[eventType] ?? humanize(rawValue)}`
  }
  if (prefix === 'page') {
    return `${t('dashboard.page')}: ${rawValue || '-'}`
  }
  if (prefix === 'source') {
    return `${t('dashboard.source')}: ${rawValue === 'web' ? t('dashboard.webSource') : humanize(rawValue)}`
  }
  return humanize(tag)
}

async function loadDashboard() {
  await dashboardState.load(() => trackingDashboard(5), { preserveData: true })
}

async function loadProfile() {
  await profileState.load(() => currentTrackingProfile(), { preserveData: true })
}

async function loadProductProfile() {
  await productState.load(() => trackingProductProfile(productId.value), { preserveData: true })
}

function togglePolling() {
  polling.value = !polling.value
  if (!polling.value) {
    visibility.stop()
    return
  }
  void loadDashboard()
  visibility.start(loadDashboard, 5000)
}

onMounted(async () => {
  await Promise.all([loadDashboard(), loadProfile(), loadProductProfile()])
  if (polling.value) {
    visibility.start(loadDashboard, 5000)
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
          {{ t('dashboard.lastUpdated', { time: generatedAt }) }}
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="dashboardState.data.value && dashboardState.status.value === 'error'"
      type="error"
      :closable="false"
      :title="t(dashboardState.error.value || 'common.requestFailed')"
      show-icon
    />

    <AsyncStateView
      :status="dashboardDisplayStatus"
      :error="dashboardState.error.value"
      @retry="loadDashboard"
    >
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
          <time v-if="myProfile?.lastEventAt" :datetime="myProfile.lastEventAt">
            {{ new Date(myProfile.lastEventAt).toLocaleString(locale) }}
          </time>
        </div>
        <AsyncStateView
          :status="profileDisplayStatus"
          :error="profileState.error.value"
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
        <div class="section-heading product-profile-heading">
          <h2 id="product-profile-title">{{ t('dashboard.productProfile') }}</h2>
          <div class="product-profile-control">
            <span>{{ t('dashboard.productId') }}</span>
            <el-input-number
              v-model="productId"
              :min="1"
              size="small"
              :aria-label="t('dashboard.productId')"
              @change="loadProductProfile"
            />
          </div>
        </div>
        <AsyncStateView
          :status="productDisplayStatus"
          :error="productState.error.value"
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
              {{ humanize(tag) }}
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
  .product-profile-heading,
  .product-profile-control {
    align-items: stretch;
    flex-direction: column;
  }

  .product-profile-control :deep(.el-input-number) {
    width: 100%;
  }
}
</style>

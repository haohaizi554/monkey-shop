<script setup lang="ts">
import { DataLine, Refresh, TrendCharts, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { currentTrackingProfile, trackingDashboard, trackingProductProfile } from '@/api/tracking'
import AppShell from '@/components/AppShell.vue'
import type { ProductProfile, RealtimeDashboard, TrackingEventType, UserProfileTag } from '@/types'

defineOptions({ name: 'DashboardView' })

const loading = ref(false)
const dashboard = ref<RealtimeDashboard>()
const myProfile = ref<UserProfileTag>()
const productProfile = ref<ProductProfile>()
const productId = ref(1)
let timer: number | undefined

const funnel = computed(() => dashboard.value?.funnel ?? [])
const generatedAt = computed(() =>
  dashboard.value?.generatedAt ? new Date(dashboard.value.generatedAt).toLocaleTimeString() : '-',
)
const profileSummary = computed(() => normalizeProfileText(myProfile.value?.profileSummary))

const eventLabels: Record<TrackingEventType, string> = {
  PAGE_VIEW: '页面访问',
  CLICK: '点击',
  SEARCH: '搜索',
  PRODUCT_VIEW: '商品浏览',
  ADD_TO_CART: '加入购物车',
  ORDER_CREATED: '创建订单',
  PAYMENT_SUCCESS: '支付成功',
}

function eventLabel(eventType: TrackingEventType): string {
  return eventLabels[eventType] ?? eventType
}

function normalizeProfileText(text?: string): string {
  if (!text) {
    return '暂无用户画像信号'
  }
  return text
    .replaceAll('PAGE_VIEW', '页面访问')
    .replaceAll('PRODUCT_VIEW', '商品浏览')
    .replaceAll('CLICK', '点击')
    .replaceAll('ADD_TO_CART', '加购')
    .replaceAll('ORDER_CREATED', '下单')
    .replaceAll('PAYMENT_SUCCESS', '支付')
    .replaceAll('SEARCH', '搜索')
    .replaceAll('last=', '最近事件=')
    .replaceAll('page=', '页面=')
    .replaceAll('previous=', '上一条=')
    .replaceAll('source=web', '来源=网页')
    .replaceAll('source:web', '来源=网页')
    .replaceAll(',', '，')
}

function tagLabel(tag: string): string {
  return tag
    .replace(/^event:/, '事件：')
    .replace(/^page:-?/, '页面：/')
    .replace(/^source:/, '来源：')
    .replaceAll('web', '网页')
    .replaceAll('page_view', '页面访问')
    .replaceAll('product_view', '商品浏览')
    .replaceAll('add_to_cart', '加购')
    .replaceAll('order_created', '下单')
    .replaceAll('payment_success', '支付')
}

async function loadDashboard() {
  loading.value = true
  try {
    dashboard.value = await trackingDashboard(5)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '数据看板加载失败')
  } finally {
    loading.value = false
  }
}

async function loadProfile() {
  try {
    myProfile.value = await currentTrackingProfile()
  } catch {
    myProfile.value = undefined
  }
}

async function loadProductProfile() {
  try {
    productProfile.value = await trackingProductProfile(productId.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '商品画像加载失败')
  }
}

onMounted(async () => {
  await Promise.all([loadDashboard(), loadProfile(), loadProductProfile()])
  timer = window.setInterval(loadDashboard, 5000)
})

onBeforeUnmount(() => {
  if (timer) {
    window.clearInterval(timer)
  }
})
</script>

<template>
  <AppShell>
    <section class="dashboard-view">
      <div class="view-toolbar">
        <div>
          <p class="eyebrow">数据中台</p>
          <h1>实时看板</h1>
        </div>
        <el-button :icon="Refresh" :loading="loading" @click="loadDashboard">
          {{ generatedAt }}
        </el-button>
      </div>

      <div class="metric-grid">
        <div class="metric-tile">
          <el-icon><DataLine /></el-icon>
          <span>PV</span>
          <strong>{{ dashboard?.pageViews ?? 0 }}</strong>
        </div>
        <div class="metric-tile">
          <el-icon><User /></el-icon>
          <span>UV</span>
          <strong>{{ dashboard?.uniqueVisitors ?? 0 }}</strong>
        </div>
        <div class="metric-tile">
          <el-icon><TrendCharts /></el-icon>
          <span>订单</span>
          <strong>{{ dashboard?.orderCount ?? 0 }}</strong>
        </div>
        <div class="metric-tile">
          <el-icon><TrendCharts /></el-icon>
          <span>GMV</span>
          <strong>{{ dashboard?.paymentAmount ?? 0 }}</strong>
        </div>
      </div>

      <section class="content-band">
        <div class="section-heading">
          <h2>转化漏斗</h2>
          <span>从搜索到支付</span>
        </div>
        <el-table :data="funnel" size="small">
          <el-table-column label="步骤">
            <template #default="{ row }">{{ eventLabel(row.eventType) }}</template>
          </el-table-column>
          <el-table-column prop="count" label="数量" width="120" />
          <el-table-column label="转化率" width="160">
            <template #default="{ row }">
              {{ (Number(row.conversionRate) * 100).toFixed(2) }}%
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="profile-grid">
        <div class="profile-panel">
          <div class="section-heading">
            <h2>用户画像</h2>
            <span>{{ myProfile?.lastEventAt ?? '-' }}</span>
          </div>
          <p>{{ profileSummary }}</p>
          <div class="tag-row">
            <el-tag v-for="tag in myProfile?.behaviorTags ?? []" :key="tag" type="info">
              {{ tagLabel(tag) }}
            </el-tag>
            <el-tag v-for="tag in myProfile?.interestTags ?? []" :key="tag" type="success">
              {{ tagLabel(tag) }}
            </el-tag>
          </div>
        </div>

        <div class="profile-panel">
          <div class="section-heading">
            <h2>商品画像</h2>
            <el-input-number
              v-model="productId"
              :min="1"
              size="small"
              @change="loadProductProfile"
            />
          </div>
          <p>
            销量 {{ productProfile?.salesCount ?? 0 }} / 评分
            {{ productProfile?.reviewScore ?? 0 }}
          </p>
          <div class="tag-row">
            <el-tag v-for="tag in productProfile?.tagVector ?? []" :key="tag">
              {{ tag }}
            </el-tag>
          </div>
        </div>
      </section>
    </section>
  </AppShell>
</template>

<style scoped>
.dashboard-view {
  display: grid;
  gap: 20px;
  min-width: 0;
}

.view-toolbar,
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.eyebrow {
  margin: 0 0 4px;
  color: var(--el-color-primary);
  font-size: 0.78rem;
  font-weight: 700;
  text-transform: uppercase;
}

h1,
h2,
p {
  margin: 0;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.metric-tile,
.profile-panel {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 16px;
  background: var(--el-bg-color);
}

.metric-tile {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 6px 10px;
  min-height: 92px;
}

.metric-tile strong {
  grid-column: 1 / -1;
  font-size: 1.9rem;
  line-height: 1;
}

.content-band {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.profile-panel {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.profile-panel p {
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.tag-row :deep(.el-tag) {
  max-width: 100%;
  height: auto;
  min-height: 24px;
  padding-block: 3px;
}

.tag-row :deep(.el-tag__content) {
  min-width: 0;
  overflow-wrap: anywhere;
  white-space: normal;
}

@media (max-width: 900px) {
  .metric-grid,
  .profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>

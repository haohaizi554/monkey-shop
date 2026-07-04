<script setup lang="ts">
import { DataLine, Refresh, TrendCharts, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { currentTrackingProfile, trackingDashboard, trackingProductProfile } from '@/api/tracking'
import AppShell from '@/components/AppShell.vue'
import type { ProductProfile, RealtimeDashboard, UserProfileTag } from '@/types'

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

async function loadDashboard() {
  loading.value = true
  try {
    dashboard.value = await trackingDashboard(5)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load dashboard')
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
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load product profile')
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
          <p class="eyebrow">Data Bank</p>
          <h1>Realtime Dashboard</h1>
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
          <span>Orders</span>
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
          <h2>Funnel</h2>
          <span>Search to payment</span>
        </div>
        <el-table :data="funnel" size="small">
          <el-table-column prop="eventType" label="Step" />
          <el-table-column prop="count" label="Count" width="120" />
          <el-table-column label="Conversion" width="160">
            <template #default="{ row }">
              {{ (Number(row.conversionRate) * 100).toFixed(2) }}%
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="profile-grid">
        <div class="profile-panel">
          <div class="section-heading">
            <h2>User Profile</h2>
            <span>{{ myProfile?.lastEventAt ?? '-' }}</span>
          </div>
          <p>{{ myProfile?.profileSummary || 'No profile signal yet' }}</p>
          <div class="tag-row">
            <el-tag v-for="tag in myProfile?.behaviorTags ?? []" :key="tag" type="info">
              {{ tag }}
            </el-tag>
            <el-tag v-for="tag in myProfile?.interestTags ?? []" :key="tag" type="success">
              {{ tag }}
            </el-tag>
          </div>
        </div>

        <div class="profile-panel">
          <div class="section-heading">
            <h2>Product Profile</h2>
            <el-input-number
              v-model="productId"
              :min="1"
              size="small"
              @change="loadProductProfile"
            />
          </div>
          <p>
            Sales {{ productProfile?.salesCount ?? 0 }} / Review
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
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.profile-panel {
  display: grid;
  gap: 12px;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

@media (max-width: 900px) {
  .metric-grid,
  .profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>

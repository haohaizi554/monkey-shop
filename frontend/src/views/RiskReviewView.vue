<script setup lang="ts">
import { CircleCheck, Lock, Refresh, Warning } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import * as riskApi from '@/api/risk'
import AppShell from '@/components/AppShell.vue'
import type {
  RiskAssessmentResponse,
  RiskDecision,
  RiskReviewCase,
  RiskReviewResolveRequest,
  RiskReviewStatus,
} from '@/types'

defineOptions({ name: 'RiskReviewView' })

const loading = ref(false)
const assessing = ref(false)
const reviews = ref<RiskReviewCase[]>([])
const assessment = ref<RiskAssessmentResponse>()

const assessmentForm = reactive({
  phone: '13800000000',
  deviceFingerprint: 'browser-fingerprint-demo',
  clientIp: '',
  productId: 1,
  orderId: undefined as number | undefined,
  seckillActivityId: undefined as number | undefined,
  sellerUserId: undefined as number | undefined,
  priceBefore: 100,
  priceAfter: 160,
  totpCode: '',
})

const resolveForms = reactive<
  Record<
    number,
    { status: RiskReviewResolveRequest['status']; resolution: string; totpCode: string }
  >
>({})

const pendingCount = computed(
  () => reviews.value.filter((item) => item.status === 'PENDING').length,
)
const blockCount = computed(() => reviews.value.filter((item) => item.status === 'BLOCKED').length)
const maxScore = computed(() => reviews.value.reduce((max, item) => Math.max(max, item.score), 0))

async function loadReviews() {
  loading.value = true
  try {
    reviews.value = await riskApi.riskReviews()
    for (const item of reviews.value) {
      if (!resolveForms[item.id]) {
        resolveForms[item.id] = { status: 'APPROVED', resolution: '', totpCode: '' }
      }
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load risk reviews')
  } finally {
    loading.value = false
  }
}

async function assessRisk() {
  assessing.value = true
  try {
    assessment.value = await riskApi.assessRisk({
      ...assessmentForm,
      orderId: assessmentForm.orderId || undefined,
      seckillActivityId: assessmentForm.seckillActivityId || undefined,
      sellerUserId: assessmentForm.sellerUserId || undefined,
      totpCode: assessmentForm.totpCode || undefined,
    })
    ElMessage.success(`Risk ${assessment.value.decision}`)
    await loadReviews()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Risk assessment failed')
  } finally {
    assessing.value = false
  }
}

async function resolveCase(item: RiskReviewCase) {
  const form = resolveForms[item.id]
  if (!form) {
    return
  }
  const updated = await riskApi.resolveRiskReview(item.id, {
    status: form.status,
    resolution: form.resolution || undefined,
    totpCode: form.totpCode || undefined,
  })
  const index = reviews.value.findIndex((review) => review.id === updated.id)
  if (index >= 0) {
    reviews.value[index] = updated
  }
  ElMessage.success(`Review ${updated.status}`)
}

function decisionType(decision?: RiskDecision): 'success' | 'warning' | 'danger' | 'info' {
  if (decision === 'ALLOW') {
    return 'success'
  }
  if (decision === 'BLOCK') {
    return 'danger'
  }
  if (decision === 'RATE_LIMIT' || decision === 'TOTP_REQUIRED') {
    return 'warning'
  }
  return 'info'
}

function statusType(status: RiskReviewStatus): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'APPROVED') {
    return 'success'
  }
  if (status === 'BLOCKED' || status === 'REJECTED') {
    return 'danger'
  }
  return 'warning'
}

onMounted(() => {
  void loadReviews()
})
</script>

<template>
  <AppShell>
    <section v-loading="loading" class="risk-page">
      <header class="risk-header">
        <div>
          <p class="eyebrow">Risk Control</p>
          <h1>Review Queue</h1>
        </div>
        <el-button :icon="Refresh" @click="loadReviews">Refresh</el-button>
      </header>

      <section class="metrics-row">
        <div class="metric-panel">
          <span>Pending</span>
          <strong>{{ pendingCount }}</strong>
        </div>
        <div class="metric-panel">
          <span>Blocked</span>
          <strong>{{ blockCount }}</strong>
        </div>
        <div class="metric-panel">
          <span>Max score</span>
          <strong>{{ maxScore }}</strong>
        </div>
      </section>

      <section class="section-grid">
        <section class="tool-panel">
          <h2>Assessment</h2>
          <div class="compact-form risk-form">
            <el-input v-model="assessmentForm.phone" placeholder="Phone" />
            <el-input v-model="assessmentForm.deviceFingerprint" placeholder="Device fingerprint" />
            <el-input v-model="assessmentForm.clientIp" placeholder="Client IP" />
            <el-input-number v-model="assessmentForm.productId" :min="1" />
            <el-input-number v-model="assessmentForm.orderId" :min="1" placeholder="Order" />
            <el-input-number
              v-model="assessmentForm.seckillActivityId"
              :min="1"
              placeholder="Seckill"
            />
            <el-input-number v-model="assessmentForm.sellerUserId" :min="1" placeholder="Seller" />
            <el-input-number v-model="assessmentForm.priceBefore" :min="0" :step="10" />
            <el-input-number v-model="assessmentForm.priceAfter" :min="0" :step="10" />
            <el-input v-model="assessmentForm.totpCode" placeholder="TOTP" />
            <el-button type="primary" :loading="assessing" :icon="Warning" @click="assessRisk">
              Assess
            </el-button>
          </div>
        </section>

        <section class="tool-panel assessment-result">
          <h2>Decision</h2>
          <template v-if="assessment">
            <el-progress
              type="dashboard"
              :percentage="assessment.score"
              :status="assessment.score >= 80 ? 'exception' : 'success'"
            />
            <el-tag :type="decisionType(assessment.decision)" effect="dark">
              {{ assessment.decision }}
            </el-tag>
            <div class="risk-flags">
              <el-tag v-if="assessment.productAutoUnlisted" type="danger">Auto unlisted</el-tag>
              <el-tag v-if="assessment.userTokensRevoked" type="danger">Tokens revoked</el-tag>
              <el-tag v-if="assessment.reviewCaseId" type="warning">
                Review #{{ assessment.reviewCaseId }}
              </el-tag>
            </div>
            <ul class="signal-list">
              <li v-for="signal in assessment.signals" :key="`${signal.type}-${signal.detail}`">
                <strong>{{ signal.type }}</strong>
                <span>{{ signal.weight }}</span>
                <small>{{ signal.detail }}</small>
              </li>
            </ul>
          </template>
          <el-empty v-else description="No assessment" />
        </section>
      </section>

      <section class="table-band">
        <div class="band-title">
          <h2>Manual Review</h2>
        </div>
        <el-table :data="reviews" class="data-table">
          <el-table-column prop="id" label="Case" width="110" />
          <el-table-column prop="userId" label="User" width="110" />
          <el-table-column prop="productId" label="Product" width="110" />
          <el-table-column prop="type" label="Signal" min-width="180" />
          <el-table-column prop="score" label="Score" width="100" />
          <el-table-column label="Status" width="130">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="Detail" min-width="260" />
          <el-table-column label="Decision" width="360" fixed="right">
            <template #default="{ row }">
              <div v-if="row.status === 'PENDING'" class="review-actions">
                <el-select v-model="resolveForms[row.id].status" size="small">
                  <el-option label="Approve" value="APPROVED" />
                  <el-option label="Reject" value="REJECTED" />
                  <el-option label="Block" value="BLOCKED" />
                </el-select>
                <el-input
                  v-model="resolveForms[row.id].resolution"
                  size="small"
                  placeholder="Resolution"
                />
                <el-input v-model="resolveForms[row.id].totpCode" size="small" placeholder="TOTP" />
                <el-button
                  size="small"
                  type="primary"
                  :icon="resolveForms[row.id].status === 'BLOCKED' ? Lock : CircleCheck"
                  @click="resolveCase(row)"
                >
                  Save
                </el-button>
              </div>
              <span v-else>{{ row.resolution || row.status }}</span>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </section>
  </AppShell>
</template>

<style scoped>
.risk-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.risk-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.risk-header h1 {
  margin: 0;
  font-size: 28px;
}

.risk-form {
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
}

.assessment-result {
  min-height: 320px;
}

.risk-flags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
}

.signal-list {
  display: grid;
  gap: 8px;
  margin: 16px 0 0;
  padding: 0;
  list-style: none;
}

.signal-list li {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) 44px minmax(160px, 2fr);
  gap: 8px;
  align-items: center;
  padding: 10px 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
}

.signal-list small {
  color: var(--text-muted);
}

.review-actions {
  display: grid;
  grid-template-columns: 100px 1fr 86px 78px;
  gap: 8px;
  align-items: center;
}

@media (max-width: 760px) {
  .risk-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .signal-list li,
  .review-actions {
    grid-template-columns: 1fr;
  }
}
</style>

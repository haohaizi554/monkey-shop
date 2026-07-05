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
  RiskSignalType,
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

const decisionLabels: Record<RiskDecision, string> = {
  ALLOW: '放行',
  RATE_LIMIT: '限流',
  TOTP_REQUIRED: '需要动态码',
  REVIEW: '人工复核',
  BLOCK: '阻断',
}

const statusLabels: Record<RiskReviewStatus, string> = {
  PENDING: '待处理',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
  BLOCKED: '已阻断',
}

const signalLabels: Record<RiskSignalType, string> = {
  DEVICE_MULTI_ACCOUNT: '设备多账号',
  PHONE_MULTI_ACCOUNT: '手机号多账号',
  SECKILL_SCALPER: '秒杀套利',
  SELF_BUY: '疑似自买',
  PRICE_ANOMALY: '价格异常',
  HIGH_RISK_SCORE: '高风险分',
  ACCOUNT_BLOCKED: '账号已阻断',
}

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
    ElMessage.error(error instanceof Error ? error.message : '风控复核列表加载失败')
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
    ElMessage.success(`风控决策：${decisionLabel(assessment.value.decision)}`)
    await loadReviews()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '风控评估失败')
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
  ElMessage.success(`复核已更新：${statusLabel(updated.status)}`)
}

function decisionLabel(decision?: RiskDecision): string {
  return decision ? (decisionLabels[decision] ?? decision) : '-'
}

function statusLabel(status: RiskReviewStatus): string {
  return statusLabels[status] ?? status
}

function signalLabel(type: RiskSignalType): string {
  return signalLabels[type] ?? type
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
          <p class="eyebrow">风控中心</p>
          <h1>复核队列</h1>
        </div>
        <el-button :icon="Refresh" @click="loadReviews">刷新</el-button>
      </header>

      <section class="metrics-row">
        <div class="metric-panel">
          <span>待处理</span>
          <strong>{{ pendingCount }}</strong>
        </div>
        <div class="metric-panel">
          <span>已阻断</span>
          <strong>{{ blockCount }}</strong>
        </div>
        <div class="metric-panel">
          <span>最高风险分</span>
          <strong>{{ maxScore }}</strong>
        </div>
      </section>

      <section class="section-grid">
        <section class="tool-panel">
          <h2>风险评估</h2>
          <div class="compact-form risk-form">
            <el-input v-model="assessmentForm.phone" placeholder="手机号" />
            <el-input v-model="assessmentForm.deviceFingerprint" placeholder="设备指纹" />
            <el-input v-model="assessmentForm.clientIp" placeholder="客户端 IP" />
            <el-input-number v-model="assessmentForm.productId" :min="1" />
            <el-input-number v-model="assessmentForm.orderId" :min="1" placeholder="订单" />
            <el-input-number
              v-model="assessmentForm.seckillActivityId"
              :min="1"
              placeholder="秒杀活动"
            />
            <el-input-number v-model="assessmentForm.sellerUserId" :min="1" placeholder="卖家" />
            <el-input-number v-model="assessmentForm.priceBefore" :min="0" :step="10" />
            <el-input-number v-model="assessmentForm.priceAfter" :min="0" :step="10" />
            <el-input v-model="assessmentForm.totpCode" placeholder="管理员动态码" />
            <el-button type="primary" :loading="assessing" :icon="Warning" @click="assessRisk">
              评估
            </el-button>
          </div>
        </section>

        <section class="tool-panel assessment-result">
          <h2>决策结果</h2>
          <template v-if="assessment">
            <el-progress
              type="dashboard"
              :percentage="assessment.score"
              :status="assessment.score >= 80 ? 'exception' : 'success'"
            />
            <el-tag :type="decisionType(assessment.decision)" effect="dark">
              {{ decisionLabel(assessment.decision) }}
            </el-tag>
            <div class="risk-flags">
              <el-tag v-if="assessment.productAutoUnlisted" type="danger">商品已自动下架</el-tag>
              <el-tag v-if="assessment.userTokensRevoked" type="danger">用户令牌已撤销</el-tag>
              <el-tag v-if="assessment.reviewCaseId" type="warning">
                复核 #{{ assessment.reviewCaseId }}
              </el-tag>
            </div>
            <ul class="signal-list">
              <li v-for="signal in assessment.signals" :key="`${signal.type}-${signal.detail}`">
                <strong>{{ signalLabel(signal.type) }}</strong>
                <span>{{ signal.weight }}</span>
                <small>{{ signal.detail }}</small>
              </li>
            </ul>
          </template>
          <el-empty v-else description="暂无评估结果" />
        </section>
      </section>

      <section class="table-band">
        <div class="band-title">
          <h2>人工复核</h2>
        </div>
        <el-table :data="reviews" class="data-table">
          <el-table-column prop="id" label="案件" width="110" />
          <el-table-column prop="userId" label="用户" width="110" />
          <el-table-column prop="productId" label="商品" width="110" />
          <el-table-column label="信号" min-width="180">
            <template #default="{ row }">{{ signalLabel(row.type) }}</template>
          </el-table-column>
          <el-table-column prop="score" label="分数" width="100" />
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="detail" label="详情" min-width="260" />
          <el-table-column label="处置" width="360" fixed="right">
            <template #default="{ row }">
              <div v-if="row.status === 'PENDING'" class="review-actions">
                <el-select v-model="resolveForms[row.id].status" size="small">
                  <el-option label="通过" value="APPROVED" />
                  <el-option label="拒绝" value="REJECTED" />
                  <el-option label="阻断" value="BLOCKED" />
                </el-select>
                <el-input
                  v-model="resolveForms[row.id].resolution"
                  size="small"
                  placeholder="处置说明"
                />
                <el-input
                  v-model="resolveForms[row.id].totpCode"
                  size="small"
                  placeholder="动态码"
                />
                <el-button
                  size="small"
                  type="primary"
                  :icon="resolveForms[row.id].status === 'BLOCKED' ? Lock : CircleCheck"
                  @click="resolveCase(row)"
                >
                  保存
                </el-button>
              </div>
              <span v-else>{{ row.resolution || statusLabel(row.status) }}</span>
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
  font-size: clamp(1.35rem, 1rem + 0.7vw, 1.8rem);
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

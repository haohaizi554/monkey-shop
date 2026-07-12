<script setup lang="ts">
import { CircleCheck, Lock, Refresh, Warning } from '@element-plus/icons-vue'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import * as riskApi from '@/api/risk'
import AdminPageToolbar from '@/components/admin/AdminPageToolbar.vue'
import MetricStrip, { type MetricItem } from '@/components/admin/MetricStrip.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import { useRouteQueryState, type RouteQuerySchema } from '@/composables/useRouteQueryState'
import type {
  RiskAssessmentResponse,
  RiskDecision,
  RiskReviewCase,
  RiskReviewResolveRequest,
  RiskReviewStatus,
  RiskSignalType,
} from '@/types'

defineOptions({ name: 'RiskReviewView' })

interface RiskQuery {
  status: '' | RiskReviewStatus
  minScore: number | null
  maxScore: number | null
}

const reviewStatuses = new Set<RiskReviewStatus>(['PENDING', 'APPROVED', 'REJECTED', 'BLOCKED'])
const riskQuerySchema: RouteQuerySchema<RiskQuery> = {
  parse(query: LocationQuery) {
    const rawStatus = String(
      Array.isArray(query.status) ? (query.status[0] ?? '') : (query.status ?? ''),
    ).toUpperCase() as RiskReviewStatus
    const rawMin = Number.parseInt(
      String(Array.isArray(query.minScore) ? (query.minScore[0] ?? '') : (query.minScore ?? '')),
      10,
    )
    const rawMax = Number.parseInt(
      String(Array.isArray(query.maxScore) ? (query.maxScore[0] ?? '') : (query.maxScore ?? '')),
      10,
    )
    return {
      status: reviewStatuses.has(rawStatus) ? rawStatus : '',
      minScore: Number.isFinite(rawMin) ? Math.max(0, Math.min(100, rawMin)) : null,
      maxScore: Number.isFinite(rawMax) ? Math.max(0, Math.min(100, rawMax)) : null,
    }
  },
  serialize(value: RiskQuery): LocationQueryRaw {
    const query: LocationQueryRaw = {}
    if (value.status) query.status = value.status
    if (value.minScore !== null) query.minScore = String(value.minScore)
    if (value.maxScore !== null) query.maxScore = String(value.maxScore)
    return query
  },
}

const { t } = useI18n()
const notify = useNotify()
const { state: filters } = useRouteQueryState(riskQuerySchema, { debounceMs: 200 })
const reviewsState = useAsyncState<RiskReviewCase[]>({ preserveData: true })
const assessmentState = useAsyncState<RiskAssessmentResponse>({ preserveData: true })
const pendingKeys = ref(new Set<string>())
const decisionDrawerOpen = ref(false)
const activeReview = ref<RiskReviewCase | null>(null)
const decisionError = ref('')
const decisionForm = reactive<{
  status: RiskReviewResolveRequest['status']
  resolution: string
  totpCode: string
}>({ status: 'APPROVED', resolution: '', totpCode: '' })
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

const reviews = computed(() => reviewsState.data.value ?? [])
const filteredReviews = computed(() =>
  reviews.value.filter((item) => {
    if (filters.status && item.status !== filters.status) return false
    if (filters.minScore !== null && item.score < filters.minScore) return false
    if (filters.maxScore !== null && item.score > filters.maxScore) return false
    return true
  }),
)
const assessment = computed(() => assessmentState.data.value)
const pendingCount = computed(
  () => reviews.value.filter((item) => item.status === 'PENDING').length,
)
const reviewMutationPending = computed(
  () => pendingKeys.value.size > 0 || assessmentState.isLoading.value,
)
const blockCount = computed(() => reviews.value.filter((item) => item.status === 'BLOCKED').length)
const maxScore = computed(() => reviews.value.reduce((max, item) => Math.max(max, item.score), 0))
const metrics = computed<MetricItem[]>(() => [
  { key: 'pending', label: t('risk.pending'), value: pendingCount.value, tone: 'warning' },
  { key: 'blocked', label: t('risk.blocked'), value: blockCount.value, tone: 'danger' },
  { key: 'max-score', label: t('risk.maxScore'), value: maxScore.value },
])

const decisionLabels = computed<Record<RiskDecision, string>>(() => ({
  ALLOW: t('risk.decisionAllow'),
  RATE_LIMIT: t('risk.decisionRateLimit'),
  TOTP_REQUIRED: t('risk.decisionTotpRequired'),
  REVIEW: t('risk.decisionReview'),
  BLOCK: t('risk.decisionBlock'),
}))
const statusLabels = computed<Record<RiskReviewStatus, string>>(() => ({
  PENDING: t('risk.statusPending'),
  APPROVED: t('risk.statusApproved'),
  REJECTED: t('risk.statusRejected'),
  BLOCKED: t('risk.statusBlocked'),
}))
const signalLabels = computed<Record<RiskSignalType, string>>(() => ({
  DEVICE_MULTI_ACCOUNT: t('risk.signalDeviceMultiAccount'),
  PHONE_MULTI_ACCOUNT: t('risk.signalPhoneMultiAccount'),
  SECKILL_SCALPER: t('risk.signalSeckillScalper'),
  SELF_BUY: t('risk.signalSelfBuy'),
  PRICE_ANOMALY: t('risk.signalPriceAnomaly'),
  HIGH_RISK_SCORE: t('risk.signalHighRiskScore'),
  ACCOUNT_BLOCKED: t('risk.signalAccountBlocked'),
}))

function isPending(key: string): boolean {
  return pendingKeys.value.has(key)
}

function setPending(key: string, value: boolean) {
  const next = new Set(pendingKeys.value)
  if (value) next.add(key)
  else next.delete(key)
  pendingKeys.value = next
}

function decisionLabel(decision?: RiskDecision): string {
  return decision ? (decisionLabels.value[decision] ?? t('common.unknown')) : '-'
}

function statusLabel(status: RiskReviewStatus): string {
  return statusLabels.value[status] ?? t('common.unknown')
}

function signalLabel(type: RiskSignalType): string {
  return signalLabels.value[type] ?? t('common.unknown')
}

function decisionType(decision?: RiskDecision): 'success' | 'warning' | 'danger' | 'info' {
  if (decision === 'ALLOW') return 'success'
  if (decision === 'BLOCK') return 'danger'
  if (decision === 'RATE_LIMIT' || decision === 'TOTP_REQUIRED') return 'warning'
  return 'info'
}

function statusType(status: RiskReviewStatus): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'APPROVED') return 'success'
  if (status === 'BLOCKED' || status === 'REJECTED') return 'danger'
  return 'warning'
}

async function loadReviews() {
  if (reviewMutationPending.value) return
  await reviewsState.load(() => riskApi.riskReviews(), {
    preserveData: true,
    isEmpty: (rows) => rows.length === 0,
  })
}

async function assessRisk() {
  if (assessmentState.isLoading.value) return
  const payload = {
    ...assessmentForm,
    orderId: assessmentForm.orderId || undefined,
    seckillActivityId: assessmentForm.seckillActivityId || undefined,
    sellerUserId: assessmentForm.sellerUserId || undefined,
    totpCode: assessmentForm.totpCode.trim() || undefined,
  }
  reviewsState.cancel()
  const result = await assessmentState.load(() => riskApi.assessRisk(payload), {
    preserveData: true,
  })
  if (result) {
    notify.success(t('risk.decisionMessage', { decision: decisionLabel(result.decision) }), {
      key: 'risk:assessment',
    })
    void loadReviews()
  }
}

function openDecision(item: RiskReviewCase, status: RiskReviewResolveRequest['status']) {
  activeReview.value = item
  decisionForm.status = status
  decisionForm.resolution = item.resolution ?? ''
  decisionForm.totpCode = ''
  decisionError.value = ''
  decisionDrawerOpen.value = true
}

async function saveDecision() {
  const item = activeReview.value
  if (!item || item.status !== 'PENDING') return
  const pendingKey = `review:${item.id}`
  if (isPending(pendingKey)) return
  decisionError.value = ''
  if (!decisionForm.resolution.trim()) {
    decisionError.value = t('risk.resolutionRequired')
    return
  }
  if (decisionForm.status === 'BLOCKED' && !decisionForm.totpCode.trim()) {
    decisionError.value = t('risk.totpRequiredForBlock')
    return
  }

  const status = decisionForm.status
  const resolution = decisionForm.resolution.trim()
  const totpCode = status === 'BLOCKED' ? decisionForm.totpCode.trim() : undefined
  setPending(pendingKey, true)
  try {
    if (status === 'BLOCKED') {
      const confirmed = await notify.confirm({
        title: t('risk.blockCaseTitle'),
        content: t('risk.blockCaseConfirm'),
        confirmText: t('risk.block'),
        type: 'warning',
      })
      if (!confirmed) return
    }
    reviewsState.cancel()
    const updated = await riskApi.resolveRiskReview(item.id, {
      status,
      resolution,
      totpCode,
    })
    const index = reviews.value.findIndex((review) => review.id === updated.id)
    if (index >= 0) reviews.value.splice(index, 1, updated)
    if (activeReview.value?.id === updated.id) activeReview.value = updated
    notify.success(t('risk.reviewUpdated', { status: statusLabel(updated.status) }), {
      key: `risk:review:${updated.id}`,
    })
  } catch (error) {
    notify.fromApiError(error, 'risk.reviewUpdateFailed')
  } finally {
    setPending(pendingKey, false)
  }
}

void loadReviews()
</script>

<template>
  <div class="route-view risk-page">
    <PageHeader
      :eyebrow="t('risk.center')"
      :title="t('risk.title')"
      :description="t('risk.description')"
    >
      <template #actions>
        <el-button
          :icon="Refresh"
          :loading="reviewsState.isLoading.value"
          :disabled="reviewMutationPending"
          @click="loadReviews"
        >
          {{ t('common.refresh') }}
        </el-button>
      </template>
    </PageHeader>

    <MetricStrip :items="metrics" />

    <section class="assessment-band" :aria-labelledby="'assessment-title'">
      <div class="assessment-tool">
        <h2 id="assessment-title">{{ t('risk.assessment') }}</h2>
        <div class="assessment-form">
          <el-input
            v-model="assessmentForm.phone"
            :disabled="assessmentState.isLoading.value"
            :aria-label="t('risk.phone')"
            :placeholder="t('risk.phone')"
          />
          <el-input
            v-model="assessmentForm.deviceFingerprint"
            :disabled="assessmentState.isLoading.value"
            :aria-label="t('risk.deviceFingerprint')"
            :placeholder="t('risk.deviceFingerprint')"
          />
          <el-input
            v-model="assessmentForm.clientIp"
            :disabled="assessmentState.isLoading.value"
            :aria-label="t('risk.clientIp')"
            :placeholder="t('risk.clientIp')"
          />
          <el-input-number
            v-model="assessmentForm.productId"
            :disabled="assessmentState.isLoading.value"
            :min="1"
            :aria-label="t('risk.product')"
          />
          <el-input-number
            v-model="assessmentForm.orderId"
            :disabled="assessmentState.isLoading.value"
            :min="1"
            :aria-label="t('risk.order')"
            :placeholder="t('risk.order')"
          />
          <el-input-number
            v-model="assessmentForm.seckillActivityId"
            :disabled="assessmentState.isLoading.value"
            :min="1"
            :aria-label="t('risk.seckillActivity')"
            :placeholder="t('risk.seckillActivity')"
          />
          <el-input-number
            v-model="assessmentForm.sellerUserId"
            :disabled="assessmentState.isLoading.value"
            :min="1"
            :aria-label="t('risk.seller')"
            :placeholder="t('risk.seller')"
          />
          <el-input-number
            v-model="assessmentForm.priceBefore"
            :disabled="assessmentState.isLoading.value"
            :min="0"
            :step="10"
            :aria-label="t('risk.priceBefore')"
          />
          <el-input-number
            v-model="assessmentForm.priceAfter"
            :disabled="assessmentState.isLoading.value"
            :min="0"
            :step="10"
            :aria-label="t('risk.priceAfter')"
          />
          <el-input
            v-model="assessmentForm.totpCode"
            :disabled="assessmentState.isLoading.value"
            :aria-label="t('risk.adminTotp')"
            :placeholder="t('risk.adminTotp')"
          />
          <el-button
            type="primary"
            :loading="assessmentState.isLoading.value"
            :disabled="assessmentState.isLoading.value"
            :icon="Warning"
            @click="assessRisk"
          >
            {{ t('risk.assess') }}
          </el-button>
        </div>
      </div>

      <div class="assessment-result" aria-live="polite">
        <h2>{{ t('risk.decisionResult') }}</h2>
        <AsyncStateView
          :status="assessmentState.status.value"
          :error="assessmentState.error.value"
          @retry="assessRisk"
        >
          <template #idle
            ><p class="empty-copy">{{ t('risk.noAssessment') }}</p></template
          >
          <div v-if="assessment" class="assessment-summary">
            <div class="score-display">
              <strong>{{ assessment.score }}</strong>
              <span>{{ t('risk.score') }}</span>
            </div>
            <el-tag :type="decisionType(assessment.decision)" effect="plain">
              {{ decisionLabel(assessment.decision) }}
            </el-tag>
            <div class="risk-flags">
              <span>{{ t('risk.automaticActions') }}:</span>
              <el-tag v-if="assessment.productAutoUnlisted" type="danger" effect="plain">
                {{ t('risk.productAutoUnlisted') }}
              </el-tag>
              <el-tag v-if="assessment.userTokensRevoked" type="danger" effect="plain">
                {{ t('risk.userTokensRevoked') }}
              </el-tag>
              <span v-if="!assessment.productAutoUnlisted && !assessment.userTokensRevoked">
                {{ t('risk.noAutomaticActions') }}
              </span>
            </div>
            <ul class="signal-list">
              <li v-for="signal in assessment.signals" :key="`${signal.type}-${signal.detail}`">
                <strong>{{ signalLabel(signal.type) }}</strong>
                <span>{{ signal.weight }}</span>
                <small>{{ signal.detail }}</small>
              </li>
            </ul>
          </div>
        </AsyncStateView>
      </div>
    </section>

    <section class="review-section" :aria-labelledby="'review-list-title'">
      <div class="section-heading">
        <h2 id="review-list-title">{{ t('risk.manualReview') }}</h2>
      </div>
      <AdminPageToolbar :aria-label="t('risk.manualReview')">
        <template #filters>
          <el-select v-model="filters.status" :aria-label="t('risk.status')" clearable>
            <el-option :label="t('risk.allStatuses')" value="" />
            <el-option :label="t('risk.statusPending')" value="PENDING" />
            <el-option :label="t('risk.statusApproved')" value="APPROVED" />
            <el-option :label="t('risk.statusRejected')" value="REJECTED" />
            <el-option :label="t('risk.statusBlocked')" value="BLOCKED" />
          </el-select>
          <el-input-number
            v-model="filters.minScore"
            :min="0"
            :max="100"
            :aria-label="t('risk.minScore')"
          />
          <el-input-number
            v-model="filters.maxScore"
            :min="0"
            :max="100"
            :aria-label="t('risk.maxScoreFilter')"
          />
        </template>
      </AdminPageToolbar>

      <AsyncStateView
        :status="reviewsState.status.value"
        :error="reviewsState.error.value"
        @retry="loadReviews"
      >
        <DataTableShell
          :aria-label="t('risk.manualReview')"
          :empty="filteredReviews.length === 0"
          :busy="reviewsState.status.value === 'updating'"
        >
          <template #empty>{{ t('common.noData') }}</template>
          <el-table :data="filteredReviews" row-key="id" size="small">
            <el-table-column prop="id" :label="t('risk.case')" width="100" />
            <el-table-column prop="userId" :label="t('risk.user')" width="100" />
            <el-table-column :label="t('risk.signal')" min-width="170">
              <template #default="{ row }">{{ signalLabel(row.type) }}</template>
            </el-table-column>
            <el-table-column prop="score" :label="t('risk.score')" width="90" />
            <el-table-column :label="t('risk.status')" width="120">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" effect="plain">{{
                  statusLabel(row.status)
                }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="detail" :label="t('risk.detail')" min-width="220" />
            <el-table-column :label="t('risk.action')" width="250" fixed="right">
              <template #default="{ row }">
                <div v-if="row.status === 'PENDING'" class="wide-review-actions">
                  <el-button
                    size="small"
                    :aria-label="t('risk.approveCase', { id: row.id })"
                    :disabled="isPending(`review:${row.id}`)"
                    @click="openDecision(row, 'APPROVED')"
                  >
                    {{ t('risk.approve') }}
                  </el-button>
                  <el-button
                    size="small"
                    :aria-label="t('risk.rejectCase', { id: row.id })"
                    :disabled="isPending(`review:${row.id}`)"
                    @click="openDecision(row, 'REJECTED')"
                  >
                    {{ t('risk.reject') }}
                  </el-button>
                  <el-button
                    size="small"
                    type="danger"
                    plain
                    :aria-label="t('risk.blockCase', { id: row.id })"
                    :disabled="isPending(`review:${row.id}`)"
                    @click="openDecision(row, 'BLOCKED')"
                  >
                    {{ t('risk.block') }}
                  </el-button>
                </div>
                <el-button
                  v-if="row.status === 'PENDING'"
                  class="mobile-review-action"
                  size="small"
                  :aria-label="t('risk.reviewAction', { id: row.id })"
                  :disabled="isPending(`review:${row.id}`)"
                  @click="openDecision(row, 'APPROVED')"
                >
                  {{ t('risk.action') }}
                </el-button>
                <span v-else>{{ row.resolution || statusLabel(row.status) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </AsyncStateView>
    </section>

    <el-drawer
      v-model="decisionDrawerOpen"
      :title="t('risk.reviewCaseTitle', { id: activeReview?.id ?? '-' })"
      size="min(460px, 94vw)"
    >
      <div v-if="activeReview" class="decision-drawer">
        <div class="decision-case-summary">
          <strong>{{ signalLabel(activeReview.type) }}</strong>
          <el-tag :type="statusType(activeReview.status)" effect="plain">
            {{ statusLabel(activeReview.status) }}
          </el-tag>
          <span>{{ t('risk.score') }}: {{ activeReview.score }}</span>
        </div>
        <el-radio-group
          v-model="decisionForm.status"
          :disabled="activeReview.status !== 'PENDING' || isPending(`review:${activeReview.id}`)"
        >
          <el-radio-button value="APPROVED">{{ t('risk.approve') }}</el-radio-button>
          <el-radio-button value="REJECTED">{{ t('risk.reject') }}</el-radio-button>
          <el-radio-button value="BLOCKED">{{ t('risk.block') }}</el-radio-button>
        </el-radio-group>
        <div class="drawer-field">
          <span>{{ t('risk.resolutionNote') }}</span>
          <el-input
            v-model="decisionForm.resolution"
            type="textarea"
            :rows="4"
            :aria-label="t('risk.resolutionNote')"
            :disabled="activeReview.status !== 'PENDING' || isPending(`review:${activeReview.id}`)"
            @input="decisionError = ''"
          />
        </div>
        <div v-if="decisionForm.status === 'BLOCKED'" class="drawer-field">
          <span>{{ t('risk.totpCode') }}</span>
          <el-input
            v-model="decisionForm.totpCode"
            :aria-label="t('risk.totpCode')"
            autocomplete="one-time-code"
            :disabled="activeReview.status !== 'PENDING' || isPending(`review:${activeReview.id}`)"
            @input="decisionError = ''"
          />
        </div>
        <p v-if="decisionError" class="decision-error" role="alert">{{ decisionError }}</p>
        <el-button
          type="primary"
          :icon="decisionForm.status === 'BLOCKED' ? Lock : CircleCheck"
          :loading="isPending(`review:${activeReview.id}`)"
          :disabled="activeReview.status !== 'PENDING' || isPending(`review:${activeReview.id}`)"
          @click="saveDecision"
        >
          {{ t('risk.saveDecision') }}
        </el-button>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.risk-page,
.review-section,
.assessment-tool,
.assessment-result {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
}

.risk-page {
  gap: var(--space-5);
}

.assessment-band {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(300px, 0.65fr);
  gap: var(--space-5);
}

.assessment-tool,
.assessment-result {
  align-content: start;
  padding-block: var(--space-4);
  border-top: 1px solid var(--color-line);
}

.assessment-tool h2,
.assessment-result h2,
.review-section h2,
.decision-error,
.empty-copy {
  margin: 0;
}

.assessment-tool h2,
.assessment-result h2,
.review-section h2 {
  font-size: var(--text-lg);
}

.assessment-form {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
}

.assessment-form :deep(.el-input-number),
.assessment-form :deep(.el-input) {
  width: 100%;
}

.assessment-summary,
.decision-drawer {
  display: grid;
  gap: var(--space-4);
}

.score-display {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}

.score-display strong {
  font-size: var(--text-3xl);
  font-variant-numeric: tabular-nums;
}

.score-display span,
.risk-flags > span,
.empty-copy {
  color: var(--color-text-muted);
}

.risk-flags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.signal-list {
  display: grid;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.signal-list li {
  display: grid;
  grid-template-columns: minmax(130px, 1fr) 44px minmax(120px, 1.5fr);
  gap: var(--space-2);
  padding-block: var(--space-2);
  border-bottom: 1px solid var(--color-line);
}

.signal-list small {
  color: var(--color-text-muted);
}

.wide-review-actions {
  display: flex;
  gap: var(--space-1);
}

.mobile-review-action {
  display: none;
}

.decision-case-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.drawer-field {
  display: grid;
  gap: var(--space-1);
}

.drawer-field span {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.decision-error {
  color: var(--color-danger);
  font-size: var(--text-sm);
}

@media (max-width: 1000px) {
  .assessment-band {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .assessment-form,
  .signal-list li {
    grid-template-columns: 1fr;
  }

  .wide-review-actions {
    display: none;
  }

  .mobile-review-action {
    display: inline-flex;
  }
}
</style>

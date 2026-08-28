<script setup lang="ts">
import { CircleCheck, Lock, Refresh, Warning, WarningFilled } from '@element-plus/icons-vue'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import * as riskApi from '@/api/risk'
import AdminPageToolbar from '@/components/admin/AdminPageToolbar.vue'
import MetricStrip, { type MetricItem } from '@/components/admin/MetricStrip.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import { useRouteQueryState, type RouteQuerySchema } from '@/composables/useRouteQueryState'
import type {
  RiskAssessmentResponse,
  RiskDecision,
  RiskReviewCase,
  RiskReviewResolveRequest,
  RiskReviewStatus,
  RiskSignal,
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
const { state: filters, replaceNow } = useRouteQueryState(riskQuerySchema, { debounceMs: 200 })
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

function pendingKey(id: number, status: RiskReviewResolveRequest['status']): string {
  return `review:${id}:${status}`
}

function isCasePending(id: number): boolean {
  return ['APPROVED', 'REJECTED', 'BLOCKED'].some((status) =>
    isPending(pendingKey(id, status as RiskReviewResolveRequest['status'])),
  )
}

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

// RiskSignal has no backend identity; type, weight, and detail are its complete
// immutable displayed fields, so this payload-neutral composite stays duplicate-safe.
function signalIdentity(signal: RiskSignal): string {
  return [signal.type, signal.weight, signal.detail ?? ''].join(':')
}

function riskRowClassName({ row }: { row: RiskReviewCase }): string {
  return `risk-table-row risk-table-row--${row.id}`
}

function statusTagStatus(status: RiskReviewStatus): string {
  return status === 'PENDING' ? 'PENDING_REVIEW' : status
}

function decisionTagStatus(decision: RiskDecision): string {
  if (decision === 'BLOCK') return 'BLOCKED'
  if (decision === 'ALLOW') return 'APPROVED'
  return 'PENDING_REVIEW'
}

function decisionType(decision?: RiskDecision): 'success' | 'warning' | 'danger' | 'info' {
  if (decision === 'ALLOW') return 'success'
  if (decision === 'BLOCK') return 'danger'
  if (decision === 'RATE_LIMIT' || decision === 'TOTP_REQUIRED') return 'warning'
  return 'info'
}

async function loadReviews() {
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
  const result = await assessmentState.load(() => riskApi.assessRisk(payload), {
    preserveData: true,
  })
  if (result) {
    notify.success(t('risk.decisionMessage', { decision: decisionLabel(result.decision) }), {
      key: 'risk:assessment',
    })
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

  const status = decisionForm.status
  const key = pendingKey(item.id, status)
  if (isCasePending(item.id)) return
  decisionError.value = ''
  if (!decisionForm.resolution.trim()) {
    decisionError.value = t('risk.resolutionRequired')
    return
  }
  if (status === 'BLOCKED' && !decisionForm.totpCode.trim()) {
    decisionError.value = t('risk.totpRequiredForBlock')
    return
  }

  const resolution = decisionForm.resolution.trim()
  const totpCode = status === 'BLOCKED' ? decisionForm.totpCode.trim() : undefined
  setPending(key, true)
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
    const updated = await riskApi.resolveRiskReview(item.id, { status, resolution, totpCode })
    reviewsState.cancel()
    const index = reviews.value.findIndex((review) => review.id === updated.id)
    if (index >= 0) reviews.value.splice(index, 1, updated)
    if (activeReview.value?.id === updated.id) activeReview.value = updated
    notify.success(t('risk.reviewUpdated', { status: statusLabel(updated.status) }), {
      key: `risk:review:${updated.id}`,
    })
  } catch {
    decisionError.value = t('risk.reviewUpdateFailed')
  } finally {
    setPending(key, false)
  }
}
async function initializeRiskQueue() {
  try {
    if (await replaceNow()) return
  } catch {
    // Keep the current route and load the queue when canonicalization is unavailable.
  }
  await loadReviews()
}

void initializeRiskQueue()
</script>

<template>
  <div class="route-view risk-page" data-surface="risk-workbench">
    <PageHeader
      :eyebrow="t('risk.center')"
      :title="t('risk.title')"
      :description="t('risk.description')"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="reviewsState.isLoading.value" @click="loadReviews">
          {{ t('common.refresh') }}
        </el-button>
      </template>
    </PageHeader>

    <MetricStrip :items="metrics" />

    <section
      class="assessment-workspace"
      data-surface="risk-assessment"
      :aria-labelledby="'assessment-title'"
    >
      <header class="workbench-section-heading">
        <div>
          <p class="section-kicker">{{ t('risk.center') }}</p>
          <h2 id="assessment-title">{{ t('risk.assessment') }}</h2>
        </div>
        <p>{{ t('risk.description') }}</p>
      </header>

      <div class="assessment-layout">
        <form class="assessment-form" @submit.prevent="assessRisk">
          <div class="assessment-form__actions">
            <p>{{ t('risk.decisionResult') }}</p>
            <el-button
              type="primary"
              native-type="submit"
              :loading="assessmentState.isLoading.value"
              :disabled="assessmentState.isLoading.value"
              :icon="Warning"
            >
              {{ t('risk.assess') }}
            </el-button>
          </div>

          <div class="assessment-fields">
            <div class="assessment-field">
              <span>{{ t('risk.phone') }}</span>
              <el-input
                id="risk-assessment-phone"
                v-model="assessmentForm.phone"
                :disabled="assessmentState.isLoading.value"
                :aria-label="t('risk.phone')"
                :placeholder="t('risk.phone')"
              />
            </div>
            <div class="assessment-field assessment-field--wide">
              <span>{{ t('risk.deviceFingerprint') }}</span>
              <el-input
                id="risk-assessment-device"
                v-model="assessmentForm.deviceFingerprint"
                :disabled="assessmentState.isLoading.value"
                :aria-label="t('risk.deviceFingerprint')"
                :placeholder="t('risk.deviceFingerprint')"
              />
            </div>
            <div class="assessment-field">
              <span>{{ t('risk.clientIp') }}</span>
              <el-input
                id="risk-assessment-client-ip"
                v-model="assessmentForm.clientIp"
                :disabled="assessmentState.isLoading.value"
                :aria-label="t('risk.clientIp')"
                :placeholder="t('risk.clientIp')"
              />
            </div>
            <div class="assessment-field">
              <span>{{ t('risk.product') }}</span>
              <el-input-number
                id="risk-assessment-product"
                v-model="assessmentForm.productId"
                :disabled="assessmentState.isLoading.value"
                :min="1"
                :aria-label="t('risk.product')"
              />
            </div>
            <div class="assessment-field">
              <span>{{ t('risk.order') }}</span>
              <el-input-number
                id="risk-assessment-order"
                v-model="assessmentForm.orderId"
                :disabled="assessmentState.isLoading.value"
                :min="1"
                :aria-label="t('risk.order')"
                :placeholder="t('risk.order')"
              />
            </div>
            <div class="assessment-field">
              <span>{{ t('risk.seckillActivity') }}</span>
              <el-input-number
                id="risk-assessment-seckill"
                v-model="assessmentForm.seckillActivityId"
                :disabled="assessmentState.isLoading.value"
                :min="1"
                :aria-label="t('risk.seckillActivity')"
                :placeholder="t('risk.seckillActivity')"
              />
            </div>
            <div class="assessment-field">
              <span>{{ t('risk.seller') }}</span>
              <el-input-number
                id="risk-assessment-seller"
                v-model="assessmentForm.sellerUserId"
                :disabled="assessmentState.isLoading.value"
                :min="1"
                :aria-label="t('risk.seller')"
                :placeholder="t('risk.seller')"
              />
            </div>
            <div class="assessment-field">
              <span>{{ t('risk.priceBefore') }}</span>
              <el-input-number
                id="risk-assessment-price-before"
                v-model="assessmentForm.priceBefore"
                :disabled="assessmentState.isLoading.value"
                :min="0"
                :step="10"
                :aria-label="t('risk.priceBefore')"
              />
            </div>
            <div class="assessment-field">
              <span>{{ t('risk.priceAfter') }}</span>
              <el-input-number
                id="risk-assessment-price-after"
                v-model="assessmentForm.priceAfter"
                :disabled="assessmentState.isLoading.value"
                :min="0"
                :step="10"
                :aria-label="t('risk.priceAfter')"
              />
            </div>
            <div class="assessment-field">
              <span>{{ t('risk.adminTotp') }}</span>
              <el-input
                id="risk-assessment-totp"
                v-model="assessmentForm.totpCode"
                :disabled="assessmentState.isLoading.value"
                :aria-label="t('risk.adminTotp')"
                :placeholder="t('risk.adminTotp')"
                autocomplete="one-time-code"
              />
            </div>
          </div>
        </form>

        <section class="assessment-result" data-surface="risk-decision-result" aria-live="polite">
          <div class="workbench-section-heading workbench-section-heading--compact">
            <h2>{{ t('risk.decisionResult') }}</h2>
          </div>
          <AsyncStateView
            :status="assessmentState.status.value"
            :error="assessmentState.error.value ? 'risk.assessFailed' : undefined"
            preserve-content-on-error
            @retry="assessRisk"
          >
            <template #idle
              ><p class="empty-copy">{{ t('risk.noAssessment') }}</p></template
            >
            <div v-if="assessment" class="assessment-summary">
              <div class="decision-summary">
                <div class="score-display" data-surface="risk-score">
                  <strong>{{ assessment.score }}</strong>
                  <span>{{ t('risk.score') }}</span>
                </div>
                <StatusTag
                  :status="decisionTagStatus(assessment.decision)"
                  :tone="decisionType(assessment.decision)"
                  :label="decisionLabel(assessment.decision)"
                />
              </div>
              <div class="risk-flags">
                <span>{{ t('risk.automaticActions') }}:</span>
                <StatusTag
                  v-if="assessment.productAutoUnlisted"
                  status="BLOCKED"
                  tone="danger"
                  :label="t('risk.productAutoUnlisted')"
                />
                <StatusTag
                  v-if="assessment.userTokensRevoked"
                  status="BLOCKED"
                  tone="danger"
                  :label="t('risk.userTokensRevoked')"
                />
                <span v-if="!assessment.productAutoUnlisted && !assessment.userTokensRevoked">
                  {{ t('risk.noAutomaticActions') }}
                </span>
              </div>
              <ul class="signal-list">
                <li
                  v-for="signal in assessment.signals"
                  :key="signalIdentity(signal)"
                  :data-signal-key="signalIdentity(signal)"
                >
                  <el-icon aria-hidden="true"><WarningFilled /></el-icon>
                  <strong>{{ signalLabel(signal.type) }}</strong>
                  <span class="signal-weight">{{ signal.weight }}</span>
                  <small v-if="signal.detail">{{ signal.detail }}</small>
                </li>
              </ul>
            </div>
          </AsyncStateView>
        </section>
      </div>
    </section>

    <section
      class="review-section"
      data-surface="risk-queue"
      data-layout="stable-detail"
      :aria-labelledby="'review-list-title'"
    >
      <header class="workbench-section-heading">
        <div>
          <p class="section-kicker">{{ t('risk.center') }}</p>
          <h2 id="review-list-title">{{ t('risk.manualReview') }}</h2>
        </div>
      </header>
      <AdminPageToolbar
        :aria-label="[t('risk.status'), t('risk.minScore'), t('risk.maxScoreFilter')].join(', ')"
      >
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
        :error="reviewsState.error.value ? 'risk.listLoadFailed' : undefined"
        preserve-content-on-error
        @retry="loadReviews"
      >
        <DataTableShell
          :aria-label="t('common.dataTable')"
          :empty="filteredReviews.length === 0"
          :busy="reviewsState.status.value === 'updating'"
        >
          <template #empty>{{ t('common.noData') }}</template>
          <el-table
            :data="filteredReviews"
            row-key="id"
            :row-class-name="riskRowClassName"
            size="small"
          >
            <el-table-column :label="t('risk.case')" width="120">
              <template #default="{ row }">
                <div class="case-cell">
                  <strong>{{ row.id }}</strong>
                  <small>{{ t('risk.reviewCase', { id: row.id }) }}</small>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="userId" :label="t('risk.user')" width="100" />
            <el-table-column :label="t('risk.signal')" min-width="170">
              <template #default="{ row }">
                <span class="signal-name">{{ signalLabel(row.type) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="score" :label="t('risk.score')" width="90" />
            <el-table-column :label="t('risk.status')" width="120">
              <template #default="{ row }">
                <StatusTag :status="statusTagStatus(row.status)" :label="statusLabel(row.status)" />
              </template>
            </el-table-column>
            <el-table-column prop="detail" :label="t('risk.detail')" min-width="220" />
            <el-table-column :label="t('risk.action')" width="250" fixed="right">
              <template #default="{ row }">
                <div
                  class="risk-row-actions"
                  :data-risk-case-id="row.id"
                  :data-row-focus="`case-${row.id}`"
                >
                  <div v-if="row.status === 'PENDING'" class="wide-review-actions">
                    <el-button
                      size="small"
                      :aria-label="t('risk.approveCase', { id: row.id })"
                      :disabled="isCasePending(row.id)"
                      @click="openDecision(row, 'APPROVED')"
                    >
                      {{ t('risk.approve') }}
                    </el-button>
                    <el-button
                      size="small"
                      :aria-label="t('risk.rejectCase', { id: row.id })"
                      :disabled="isCasePending(row.id)"
                      @click="openDecision(row, 'REJECTED')"
                    >
                      {{ t('risk.reject') }}
                    </el-button>
                    <el-button
                      size="small"
                      type="danger"
                      plain
                      :aria-label="t('risk.blockCase', { id: row.id })"
                      :disabled="isCasePending(row.id)"
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
                    :disabled="isCasePending(row.id)"
                    @click="openDecision(row, 'APPROVED')"
                  >
                    {{ t('risk.action') }}
                  </el-button>
                  <span v-else>{{ row.resolution || statusLabel(row.status) }}</span>
                </div>
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
      data-surface="risk-decision-panel"
      :data-risk-case-id="activeReview?.id ?? undefined"
    >
      <div v-if="activeReview" class="decision-drawer" data-surface="risk-decision-content">
        <div class="decision-case-summary">
          <div class="decision-case-summary__signal">
            <strong>{{ signalLabel(activeReview.type) }}</strong>
            <span>{{ t('risk.score') }}: {{ activeReview.score }}</span>
          </div>
          <StatusTag
            :status="statusTagStatus(activeReview.status)"
            :label="statusLabel(activeReview.status)"
          />
        </div>
        <el-radio-group
          v-model="decisionForm.status"
          :aria-label="t('risk.action')"
          :disabled="activeReview.status !== 'PENDING' || isCasePending(activeReview.id)"
        >
          <el-radio-button value="APPROVED">{{ t('risk.approve') }}</el-radio-button>
          <el-radio-button value="REJECTED">{{ t('risk.reject') }}</el-radio-button>
          <el-radio-button value="BLOCKED">{{ t('risk.block') }}</el-radio-button>
        </el-radio-group>
        <div class="drawer-field">
          <span>{{ t('risk.resolutionNote') }}</span>
          <el-input
            id="risk-resolution-note"
            v-model="decisionForm.resolution"
            type="textarea"
            :rows="4"
            :aria-label="t('risk.resolutionNote')"
            :disabled="activeReview.status !== 'PENDING' || isCasePending(activeReview.id)"
            @input="decisionError = ''"
          />
        </div>
        <div v-if="decisionForm.status === 'BLOCKED'" class="drawer-field">
          <span>{{ t('risk.totpCode') }}</span>
          <el-input
            id="risk-decision-totp"
            v-model="decisionForm.totpCode"
            :aria-label="t('risk.totpCode')"
            autocomplete="one-time-code"
            :disabled="activeReview.status !== 'PENDING' || isCasePending(activeReview.id)"
            @input="decisionError = ''"
          />
        </div>
        <p v-if="decisionError" class="decision-error" role="alert">{{ decisionError }}</p>
        <el-button
          class="decision-submit"
          type="primary"
          :icon="decisionForm.status === 'BLOCKED' ? Lock : CircleCheck"
          :loading="isCasePending(activeReview.id)"
          :disabled="activeReview.status !== 'PENDING' || isCasePending(activeReview.id)"
          @click="saveDecision"
        >
          {{ t('risk.saveDecision') }}
        </el-button>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.risk-page {
  display: grid;
  gap: var(--space-6);
  width: 100%;
  min-width: 0;
  max-width: 100%;
}

.assessment-workspace,
.review-section {
  display: grid;
  gap: var(--space-5);
  min-width: 0;
  padding-block: var(--space-5);
  border-block: 1px solid var(--admin-line);
  background: transparent;
}

.workbench-section-heading {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  align-items: flex-start;
  justify-content: space-between;
  min-width: 0;
}

.workbench-section-heading > * {
  min-width: 0;
}

.workbench-section-heading h2,
.workbench-section-heading p,
.decision-error,
.empty-copy {
  margin: 0;
}

.workbench-section-heading h2 {
  color: var(--admin-ink);
  font-size: var(--text-lg);
  line-height: var(--leading-tight);
}

.workbench-section-heading > p {
  max-width: 58ch;
  color: var(--admin-muted);
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
}

.workbench-section-heading--compact {
  align-items: center;
}

.section-kicker {
  margin: 0 0 var(--space-1);
  color: var(--admin-accent);
  font-size: var(--text-xs);
  font-weight: var(--font-weight-bold);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.assessment-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(280px, 0.65fr);
  gap: var(--space-6);
  min-width: 0;
}

.assessment-form {
  display: grid;
  align-content: start;
  gap: var(--space-4);
  min-width: 0;
}

.assessment-form__actions {
  display: flex;
  gap: var(--space-4);
  align-items: center;
  justify-content: space-between;
  min-width: 0;
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--admin-line);
}

.assessment-form__actions p {
  min-width: 0;
  margin: 0;
  color: var(--admin-muted);
  font-size: var(--text-sm);
  font-weight: var(--font-weight-semibold);
}

.assessment-form__actions :deep(.el-button) {
  flex: 0 0 auto;
  min-width: 126px;
  min-height: var(--control-height-compact);
}

.assessment-fields {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-3);
  min-width: 0;
}

.assessment-field {
  display: grid;
  gap: var(--space-1);
  min-width: 0;
}

.assessment-field--wide {
  grid-column: span 2;
}

.assessment-field > span {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--admin-muted);
  font-size: var(--text-xs);
  font-weight: var(--font-weight-semibold);
  line-height: var(--leading-snug);
}

.assessment-field :deep(.el-input),
.assessment-field :deep(.el-input-number) {
  width: 100%;
  min-width: 0;
  max-width: 100%;
}

.assessment-field :deep(.el-input__wrapper),
.assessment-field :deep(.el-input-number) {
  min-height: var(--control-height-compact);
}

.assessment-field :deep(.el-input__inner) {
  min-height: var(--control-height-compact);
}

.assessment-result {
  display: grid;
  align-content: start;
  gap: var(--space-4);
  min-width: 0;
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
  color: var(--admin-ink);
  font-size: var(--text-3xl);
  font-variant-numeric: tabular-nums;
  line-height: var(--leading-tight);
}

.score-display span,
.risk-flags > span,
.empty-copy {
  color: var(--admin-muted);
}

.decision-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  align-items: center;
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
  border-top: 1px solid var(--admin-line);
  list-style: none;
}

.signal-list li {
  display: grid;
  grid-template-columns: auto minmax(110px, 1fr) auto;
  gap: var(--space-2);
  align-items: start;
  min-width: 0;
  padding-block: var(--space-3);
  border-bottom: 1px solid var(--admin-line);
}

.signal-list li > .el-icon {
  margin-top: 2px;
  color: var(--admin-warning);
}

.signal-list small {
  grid-column: 2 / -1;
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--admin-muted);
  font-size: var(--text-xs);
  line-height: var(--leading-normal);
}

.signal-weight {
  color: var(--admin-warning);
  font-variant-numeric: tabular-nums;
  font-weight: var(--font-weight-bold);
}

.wide-review-actions {
  display: flex;
  gap: var(--space-1);
  align-items: center;
}

.risk-row-actions {
  display: flex;
  min-width: 0;
  align-items: center;
}

.mobile-review-action {
  display: none;
}

.case-cell {
  display: grid;
  gap: var(--space-1);
  min-width: 0;
}

.case-cell strong,
.signal-name {
  color: var(--admin-ink);
}

.case-cell small {
  overflow: hidden;
  color: var(--admin-muted);
  font-size: var(--text-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.decision-case-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  min-width: 0;
  padding-bottom: var(--space-4);
  border-bottom: 1px solid var(--admin-line);
}

.decision-case-summary__signal {
  display: grid;
  gap: var(--space-1);
  min-width: 0;
}

.decision-case-summary__signal strong {
  overflow-wrap: anywhere;
  color: var(--admin-ink);
}

.decision-case-summary__signal span,
.drawer-field > span {
  color: var(--admin-muted);
  font-size: var(--text-sm);
}

.drawer-field {
  display: grid;
  gap: var(--space-2);
  min-width: 0;
}

.drawer-field :deep(.el-input),
.drawer-field :deep(.el-textarea) {
  width: 100%;
  min-width: 0;
  max-width: 100%;
}

.drawer-field :deep(.el-input__wrapper) {
  min-height: var(--control-height);
}

.drawer-field :deep(.el-input__inner) {
  min-height: var(--control-height);
}

.drawer-field :deep(.el-textarea__inner) {
  min-height: 116px;
  resize: vertical;
}

.decision-error {
  color: var(--admin-danger);
  font-size: var(--text-sm);
}

.decision-submit {
  width: 100%;
  min-height: var(--control-height);
}

@media (max-width: 1200px) {
  .assessment-fields {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .assessment-field--wide {
    grid-column: span 2;
  }
}

@media (max-width: 1000px) {
  .assessment-layout {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .risk-page {
    gap: var(--space-4);
  }

  .assessment-workspace,
  .review-section {
    gap: var(--space-4);
    padding-block: var(--space-4);
  }

  .risk-page > .review-section {
    order: 1;
  }

  .risk-page > .assessment-workspace {
    order: 2;
  }

  .assessment-layout {
    gap: var(--space-4);
  }

  .assessment-fields {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--space-3);
  }

  .assessment-field--wide {
    grid-column: 1 / -1;
  }

  .assessment-form__actions {
    box-sizing: border-box;
    padding: var(--space-2);
    border: 1px solid var(--admin-line-strong);
    border-radius: var(--radius-control);
    background: var(--admin-surface-raised);
    box-shadow: var(--shadow-control);
  }

  .assessment-form__actions p {
    display: none;
  }

  .assessment-form__actions :deep(.el-button) {
    width: 100%;
    min-width: 0;
    min-height: var(--touch-target-min);
  }

  .assessment-field :deep(.el-input__wrapper),
  .assessment-field :deep(.el-input-number),
  .assessment-field :deep(.el-input__inner) {
    min-height: var(--touch-target-min);
  }

  .assessment-field :deep(.el-input__inner) {
    height: var(--touch-target-min);
  }

  .assessment-field :deep(.el-input-number__decrease),
  .assessment-field :deep(.el-input-number__increase) {
    min-height: var(--touch-target-min);
  }

  .decision-drawer :deep(.el-radio-button) {
    display: inline-flex;
    min-height: var(--touch-target-min);
  }

  .decision-drawer :deep(.el-radio-button__inner) {
    display: inline-flex;
    min-height: var(--touch-target-min);
    align-items: center;
  }

  .assessment-result:has(.async-state-view[data-status='idle']) {
    display: none;
  }

  .decision-case-summary {
    align-items: flex-start;
  }

  .decision-submit {
    min-height: var(--touch-target-min);
  }
}
@media (max-width: 760px) {
  .signal-list li {
    grid-template-columns: 1fr;
  }

  .wide-review-actions {
    display: none;
  }

  .mobile-review-action {
    display: inline-flex;
    min-height: var(--touch-target-min);
  }
}

@media (max-width: 360px) {
  .assessment-fields {
    gap: var(--space-2);
  }

  .workbench-section-heading {
    gap: var(--space-2);
  }
}

@media (prefers-reduced-motion: reduce) {
  .risk-page :is(.assessment-form__actions, .risk-row-actions, .signal-list) {
    animation-duration: 1ms !important;
    transition-duration: 1ms !important;
  }
}
</style>

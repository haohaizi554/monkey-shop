<script setup lang="ts">
import { Lightning, PriceTag, RefreshRight, UserFilled } from '@element-plus/icons-vue'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  claimCoupon,
  createSeckillOrder,
  joinGroupBuy,
  quoteMarketingPrice,
  redeemCoupon,
  returnCoupon,
} from '@/api/marketing'
import { isPositiveApiId, type ApiId } from '@/api/ids'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag, { type StatusTone } from '@/components/ui/StatusTag.vue'
import { useNotify } from '@/composables/useNotify'
import type { CouponWalletEntry, GroupBuyTeam, MarketingPriceQuote, SeckillOrder } from '@/types'
import { money } from '@/utils/format'

defineOptions({ name: 'MarketingView' })

type TaskKey = 'coupon' | 'quote' | 'seckill' | 'group'

const { t } = useI18n()
const notify = useNotify()
const couponId = ref<ApiId>('2400000000001')
const couponClaimKey = ref(`coupon-${Date.now()}`)
const couponCode = ref('')
const couponOrderId = ref<ApiId | null>(null)
const quoteAmount = ref(128)
const quoteCouponCodes = ref('PLATFORM-20,SHOP-10')
const seckillActivityId = ref<ApiId>('2500000000001')
const seckillQuantity = ref(1)
const seckillOrderKey = ref(`flash-${Date.now()}`)
const groupActivityId = ref<ApiId>('2600000000001')
const groupTeamId = ref<ApiId | null>(null)
const groupKey = ref(`group-${Date.now()}`)
const pendingKeys = ref(new Set<string>())
const taskErrors = reactive<Record<TaskKey, string>>({
  coupon: '',
  quote: '',
  seckill: '',
  group: '',
})
const latestCoupon = ref<CouponWalletEntry | null>(null)
const latestQuote = ref<MarketingPriceQuote | null>(null)
const latestSeckill = ref<SeckillOrder | null>(null)
const latestGroup = ref<GroupBuyTeam | null>(null)

const parsedCouponCodes = computed(() =>
  quoteCouponCodes.value
    .split(',')
    .map((code) => code.trim())
    .filter(Boolean),
)

function isPending(key: string): boolean {
  return pendingKeys.value.has(key)
}

function setPending(key: string, value: boolean) {
  const next = new Set(pendingKeys.value)
  if (value) next.add(key)
  else next.delete(key)
  pendingKeys.value = next
}

function validate(task: TaskKey, condition: boolean, messageKey: string): boolean {
  taskErrors[task] = condition ? '' : t(messageKey)
  return condition
}

function couponStatus(status: string): string {
  const labels: Record<string, string> = {
    CLAIMED: 'marketing.couponClaimedStatus',
    USED: 'marketing.couponRedeemedStatus',
    RETURNED: 'marketing.couponReturnedStatus',
    EXPIRED: 'marketing.couponExpiredStatus',
  }
  return t(labels[status] ?? 'marketing.statusUnavailable')
}

function couponStatusTone(status: string): StatusTone {
  if (status === 'CLAIMED') return 'success'
  if (status === 'USED') return 'info'
  if (status === 'RETURNED') return 'neutral'
  if (status === 'EXPIRED') return 'warning'
  return 'neutral'
}

function groupStatus(status: string): string {
  const labels: Record<string, string> = {
    OPEN: 'marketing.groupOpenStatus',
    SUCCEEDED: 'marketing.groupSucceededStatus',
    CANCELLED: 'marketing.groupCancelledStatus',
  }
  return t(labels[status] ?? 'marketing.statusUnavailable')
}

function groupStatusTone(status: string): StatusTone {
  if (status === 'OPEN') return 'info'
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'CANCELLED') return 'danger'
  return 'neutral'
}

function groupSummary(group: GroupBuyTeam): string {
  return t('marketing.groupSummary', {
    id: group.id,
    joined: group.joinedCount,
    target: group.targetSize,
    status: '',
  }).replace(/[,\uFF0C]\s*$/, '')
}

async function runClaimCoupon() {
  if (!validate('coupon', isPositiveApiId(couponId.value), 'marketing.positiveValueRequired'))
    return
  if (!validate('coupon', Boolean(couponClaimKey.value.trim()), 'marketing.idempotencyKeyRequired'))
    return
  const key = 'coupon:claim'
  setPending(key, true)
  try {
    latestCoupon.value = await claimCoupon({
      couponId: couponId.value,
      idempotencyKey: couponClaimKey.value.trim(),
    })
    couponCode.value = latestCoupon.value.couponCode
    taskErrors.coupon = ''
    notify.success(t('marketing.couponClaimed'), { key: 'marketing:coupon:claim' })
  } catch (error) {
    taskErrors.coupon = t('marketing.couponClaimFailed')
    notify.fromApiError(error, 'marketing.couponClaimFailed')
  } finally {
    setPending(key, false)
  }
}

async function runRedeemCoupon() {
  if (
    !validate(
      'coupon',
      Boolean(couponCode.value.trim() && isPositiveApiId(couponOrderId.value)),
      'marketing.couponCodeAndOrderRequired',
    )
  )
    return
  const key = 'coupon:redeem'
  setPending(key, true)
  try {
    latestCoupon.value = await redeemCoupon({
      couponCode: couponCode.value.trim(),
      orderId: couponOrderId.value!,
    })
    taskErrors.coupon = ''
    notify.success(t('marketing.couponRedeemed'), { key: 'marketing:coupon:redeem' })
  } catch (error) {
    taskErrors.coupon = t('marketing.couponRedeemFailed')
    notify.fromApiError(error, 'marketing.couponRedeemFailed')
  } finally {
    setPending(key, false)
  }
}

async function runReturnCoupon() {
  if (
    !validate(
      'coupon',
      Boolean(couponCode.value.trim() && isPositiveApiId(couponOrderId.value)),
      'marketing.couponCodeAndOrderRequired',
    )
  )
    return
  const key = 'coupon:return'
  setPending(key, true)
  try {
    latestCoupon.value = await returnCoupon({
      couponCode: couponCode.value.trim(),
      orderId: couponOrderId.value!,
    })
    taskErrors.coupon = ''
    notify.success(t('marketing.couponReturned'), { key: 'marketing:coupon:return' })
  } catch (error) {
    taskErrors.coupon = t('marketing.couponReturnFailed')
    notify.fromApiError(error, 'marketing.couponReturnFailed')
  } finally {
    setPending(key, false)
  }
}

async function runQuote() {
  if (!validate('quote', quoteAmount.value > 0, 'marketing.positiveValueRequired')) return
  const key = 'quote'
  setPending(key, true)
  try {
    latestQuote.value = await quoteMarketingPrice({
      orderAmount: quoteAmount.value,
      shopId: 1,
      couponCodes: parsedCouponCodes.value,
    })
    taskErrors.quote = ''
    notify.success(t('marketing.quoteReady'), { key: 'marketing:quote' })
  } catch (error) {
    taskErrors.quote = t('marketing.quoteFailed')
    notify.fromApiError(error, 'marketing.quoteFailed')
  } finally {
    setPending(key, false)
  }
}

async function runSeckill() {
  if (
    !validate(
      'seckill',
      isPositiveApiId(seckillActivityId.value) && seckillQuantity.value > 0,
      'marketing.positiveValueRequired',
    )
  )
    return
  if (
    !validate('seckill', Boolean(seckillOrderKey.value.trim()), 'marketing.idempotencyKeyRequired')
  )
    return
  const key = 'seckill'
  setPending(key, true)
  try {
    latestSeckill.value = await createSeckillOrder({
      activityId: seckillActivityId.value,
      quantity: seckillQuantity.value,
      idempotencyKey: seckillOrderKey.value.trim(),
    })
    taskErrors.seckill = ''
    notify.success(t('marketing.seckillAccepted'), { key: 'marketing:seckill' })
  } catch (error) {
    taskErrors.seckill = t('marketing.seckillFailed')
    notify.fromApiError(error, 'marketing.seckillFailed')
  } finally {
    setPending(key, false)
  }
}

async function runJoinGroup() {
  if (
    !validate(
      'group',
      isPositiveApiId(groupActivityId.value) &&
        (groupTeamId.value === null || isPositiveApiId(groupTeamId.value)),
      'marketing.positiveValueRequired',
    )
  )
    return
  if (!validate('group', Boolean(groupKey.value.trim()), 'marketing.idempotencyKeyRequired')) return
  const key = 'group-buy'
  setPending(key, true)
  try {
    latestGroup.value = await joinGroupBuy({
      activityId: groupActivityId.value,
      teamId: groupTeamId.value ?? undefined,
      idempotencyKey: groupKey.value.trim(),
    })
    groupTeamId.value = latestGroup.value.id
    taskErrors.group = ''
    notify.success(t('marketing.groupUpdated'), { key: 'marketing:group' })
  } catch (error) {
    taskErrors.group = t('marketing.groupFailed')
    notify.fromApiError(error, 'marketing.groupFailed')
  } finally {
    setPending(key, false)
  }
}
</script>

<template>
  <div
    class="route-view marketing-page commerce-page"
    data-surface="marketing-observatory"
    data-layout="operation-board"
  >
    <PageHeader
      :eyebrow="t('nav.admin')"
      :title="t('marketing.title')"
      :description="t('marketing.description')"
    />

    <div class="marketing-task-grid" data-layout="operation-board">
      <section
        class="marketing-tool commerce-section"
        data-surface="operation-section"
        data-task="coupon"
        aria-labelledby="coupon-task-title"
      >
        <header class="tool-heading">
          <div>
            <h2 id="coupon-task-title">
              <el-icon aria-hidden="true"><PriceTag /></el-icon>{{ t('marketing.coupon') }}
            </h2>
            <p>{{ t('marketing.couponDescription') }}</p>
          </div>
        </header>
        <div class="task-form-grid">
          <div class="field-control">
            <span>{{ t('marketing.couponId') }}</span
            ><el-input v-model="couponId" :aria-label="t('marketing.couponId')" />
          </div>
          <div class="field-control">
            <span>{{ t('marketing.idempotencyKey') }}</span
            ><el-input v-model="couponClaimKey" :aria-label="t('marketing.idempotencyKey')" />
          </div>
          <el-button type="primary" :loading="isPending('coupon:claim')" @click="runClaimCoupon">{{
            t('marketing.claim')
          }}</el-button>
        </div>
        <div class="operation-divider" aria-hidden="true" />
        <div class="task-form-grid">
          <div class="field-control">
            <span>{{ t('marketing.couponCode') }}</span
            ><el-input v-model="couponCode" :aria-label="t('marketing.couponCode')" />
          </div>
          <div class="field-control">
            <span>{{ t('marketing.orderId') }}</span
            ><el-input v-model="couponOrderId" :aria-label="t('marketing.orderId')" />
          </div>
          <div class="task-actions">
            <el-button :loading="isPending('coupon:redeem')" @click="runRedeemCoupon">{{
              t('marketing.redeem')
            }}</el-button
            ><el-button
              :icon="RefreshRight"
              :loading="isPending('coupon:return')"
              @click="runReturnCoupon"
              >{{ t('marketing.return') }}</el-button
            >
          </div>
        </div>
        <div class="task-feedback" aria-live="polite">
          <p v-if="taskErrors.coupon" data-testid="coupon-error" class="task-error" role="alert">
            {{ taskErrors.coupon }}
          </p>
          <div v-if="latestCoupon" data-testid="coupon-result" class="task-result" role="status">
            <span>{{ t('marketing.latestResult') }}</span
            ><strong>{{ latestCoupon.couponCode }}</strong
            ><StatusTag
              :status="latestCoupon.status"
              :label="couponStatus(latestCoupon.status)"
              :tone="couponStatusTone(latestCoupon.status)"
            />
          </div>
        </div>
      </section>

      <section
        class="marketing-tool commerce-section"
        data-surface="operation-section"
        data-task="quote"
        aria-labelledby="quote-task-title"
      >
        <header class="tool-heading">
          <div>
            <h2 id="quote-task-title">
              <el-icon aria-hidden="true"><PriceTag /></el-icon>{{ t('marketing.priceQuote') }}
            </h2>
            <p>{{ t('marketing.quoteDescription') }}</p>
          </div>
        </header>
        <div class="quote-workbench">
          <div class="quote-form">
            <div class="task-form-grid">
              <div class="field-control">
                <span>{{ t('marketing.orderAmount') }}</span
                ><el-input-number
                  v-model="quoteAmount"
                  controls-position="right"
                  :aria-label="t('marketing.orderAmount')"
                />
              </div>
              <div class="field-control">
                <span>{{ t('marketing.couponCodesHint') }}</span
                ><el-input
                  v-model="quoteCouponCodes"
                  :aria-label="t('marketing.couponCodesHint')"
                />
              </div>
              <el-button type="primary" :loading="isPending('quote')" @click="runQuote">{{
                t('marketing.quote')
              }}</el-button>
            </div>
            <p v-if="taskErrors.quote" data-testid="quote-error" class="task-error" role="alert">
              {{ taskErrors.quote }}
            </p>
          </div>
          <div data-testid="quote-result" class="quote-outcome" role="status" aria-live="polite">
            <template v-if="latestQuote">
              <dl class="quote-totals">
                <div>
                  <dt>{{ t('marketing.originalLabel') }}</dt>
                  <dd>{{ money(latestQuote.originalAmount) }}</dd>
                </div>
                <div>
                  <dt>{{ t('marketing.discountLabel') }}</dt>
                  <dd>{{ money(latestQuote.discountAmount) }}</dd>
                </div>
                <div class="quote-total--payable">
                  <dt>{{ t('marketing.payableLabel') }}</dt>
                  <dd>{{ money(latestQuote.payableAmount) }}</dd>
                </div>
              </dl>
              <div class="applied-coupons">
                <span>{{ t('marketing.appliedCoupons') }}</span>
                <div v-if="latestQuote.appliedCoupons.length" class="coupon-tags">
                  <el-tag v-for="code in latestQuote.appliedCoupons" :key="code" effect="plain">{{
                    code
                  }}</el-tag>
                </div>
                <span v-else>{{ t('marketing.noAppliedCoupons') }}</span>
              </div>
            </template>
            <p v-else class="quote-placeholder">{{ t('marketing.quotePlaceholder') }}</p>
          </div>
        </div>
      </section>

      <section
        class="marketing-tool commerce-section"
        data-surface="operation-section"
        data-task="seckill"
        aria-labelledby="seckill-task-title"
      >
        <header class="tool-heading">
          <div>
            <h2 id="seckill-task-title">
              <el-icon aria-hidden="true"><Lightning /></el-icon>{{ t('marketing.seckill') }}
            </h2>
            <p>{{ t('marketing.seckillDescription') }}</p>
          </div>
        </header>
        <div class="task-form-grid">
          <div class="field-control">
            <span>{{ t('marketing.activityId') }}</span
            ><el-input v-model="seckillActivityId" :aria-label="t('marketing.activityId')" />
          </div>
          <div class="field-control">
            <span>{{ t('marketing.quantity') }}</span
            ><el-input-number
              v-model="seckillQuantity"
              controls-position="right"
              :aria-label="t('marketing.quantity')"
            />
          </div>
          <div class="field-control field-control--wide">
            <span>{{ t('marketing.idempotencyKey') }}</span
            ><el-input v-model="seckillOrderKey" :aria-label="t('marketing.idempotencyKey')" />
          </div>
          <el-button type="primary" :loading="isPending('seckill')" @click="runSeckill">{{
            t('marketing.submitSeckill')
          }}</el-button>
        </div>
        <div class="task-feedback" aria-live="polite">
          <p v-if="taskErrors.seckill" data-testid="seckill-error" class="task-error" role="alert">
            {{ taskErrors.seckill }}
          </p>
          <div v-if="latestSeckill" data-testid="seckill-result" class="task-result" role="status">
            <span>{{ t('marketing.latestResult') }}</span
            ><strong>{{ t('marketing.seckillOrder', { id: latestSeckill.id }) }}</strong>
          </div>
        </div>
      </section>

      <section
        class="marketing-tool commerce-section"
        data-surface="operation-section"
        data-task="group"
        aria-labelledby="group-task-title"
      >
        <header class="tool-heading">
          <div>
            <h2 id="group-task-title">
              <el-icon aria-hidden="true"><UserFilled /></el-icon>{{ t('marketing.groupBuy') }}
            </h2>
            <p>{{ t('marketing.groupDescription') }}</p>
          </div>
        </header>
        <div class="task-form-grid">
          <div class="field-control">
            <span>{{ t('marketing.activityId') }}</span
            ><el-input v-model="groupActivityId" :aria-label="t('marketing.activityId')" />
          </div>
          <div class="field-control">
            <span>{{ t('marketing.teamId') }}</span
            ><el-input v-model="groupTeamId" :aria-label="t('marketing.teamId')" />
          </div>
          <div class="field-control field-control--wide">
            <span>{{ t('marketing.idempotencyKey') }}</span
            ><el-input v-model="groupKey" :aria-label="t('marketing.idempotencyKey')" />
          </div>
          <el-button type="primary" :loading="isPending('group-buy')" @click="runJoinGroup">{{
            t('marketing.joinGroupBuy')
          }}</el-button>
        </div>
        <div class="task-feedback" aria-live="polite">
          <p v-if="taskErrors.group" data-testid="group-error" class="task-error" role="alert">
            {{ taskErrors.group }}
          </p>
          <div v-if="latestGroup" data-testid="group-result" class="task-result" role="status">
            <span>{{ t('marketing.latestResult') }}</span
            ><strong>{{ groupSummary(latestGroup) }}</strong
            ><StatusTag
              :status="latestGroup.status"
              :label="groupStatus(latestGroup.status)"
              :tone="groupStatusTone(latestGroup.status)"
            />
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.marketing-page {
  display: grid;
  gap: var(--space-5);
  width: 100%;
  max-width: 100%;
  container-type: inline-size;
  min-width: 0;
}

.marketing-task-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  align-items: start;
  min-width: 0;
}

.marketing-tool {
  display: grid;
  align-content: start;
  gap: var(--space-4);
  min-width: 0;
  padding-block: var(--space-5);
  border-block: 1px solid var(--admin-line);
  background: transparent;
}

.marketing-tool:nth-child(2n + 1) {
  border-inline-end: 1px solid var(--admin-line);
  padding-inline-end: var(--space-5);
}

.marketing-tool:nth-child(2n) {
  padding-inline-start: var(--space-5);
}

.tool-heading h2 {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  color: var(--admin-ink);
  font-size: var(--text-lg);
  line-height: var(--leading-tight);
}

.tool-heading h2 :deep(.el-icon) {
  color: var(--admin-accent);
}

.tool-heading p,
.field-control span,
.task-result > span,
.applied-coupons > span,
.quote-placeholder {
  margin: var(--space-1) 0 0;
  color: var(--admin-muted);
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
}

.task-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: end;
  gap: var(--space-3);
  min-width: 0;
}

.field-control {
  display: grid;
  gap: var(--space-2);
  min-width: 0;
}

.field-control span {
  color: var(--admin-ink);
  font-weight: var(--font-weight-semibold);
}

.field-control--wide,
.task-actions {
  grid-column: 1 / -1;
}

.field-control :deep(.el-input-number),
.field-control :deep(.el-input) {
  width: 100%;
}

.task-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  min-width: 0;
}

.operation-divider {
  height: 1px;
  background: var(--admin-line);
}

.task-feedback {
  display: grid;
  align-content: start;
  min-block-size: 2.5rem;
}

.task-error {
  margin: 0;
  color: var(--admin-danger);
  font-size: var(--text-sm);
}

.task-result {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--admin-line);
}

.task-result strong {
  min-width: 0;
  color: var(--admin-ink);
  overflow-wrap: anywhere;
}

.quote-workbench {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(12rem, 0.9fr);
  gap: var(--space-4);
  align-items: stretch;
}

.quote-form {
  display: grid;
  align-content: start;
  gap: var(--space-3);
  min-width: 0;
}

.quote-outcome {
  display: grid;
  align-content: start;
  gap: var(--space-3);
  min-block-size: 10.75rem;
  padding-inline-start: var(--space-4);
  border-inline-start: 1px solid var(--admin-line);
}

.quote-placeholder {
  align-self: center;
  margin: 0;
}

.quote-totals {
  display: grid;
  gap: var(--space-2);
  margin: 0;
}

.quote-totals > div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) max-content;
  gap: var(--space-3);
}

.quote-totals dt {
  color: var(--admin-muted);
  font-size: var(--text-sm);
}

.quote-totals dd {
  margin: 0;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.quote-total--payable {
  padding-top: var(--space-2);
  border-top: 1px solid var(--admin-line);
}

.quote-total--payable dd {
  color: var(--admin-accent);
  font-weight: 700;
}

.applied-coupons,
.coupon-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.applied-coupons {
  padding-top: var(--space-2);
  border-top: 1px solid var(--admin-line);
}

.marketing-tool :deep(.el-button) {
  white-space: normal;
}

@container (max-width: 900px) {
  .marketing-task-grid {
    grid-template-columns: 1fr;
  }

  .marketing-tool:nth-child(2n + 1),
  .marketing-tool:nth-child(2n) {
    padding-inline: 0;
    border-inline-end: 0;
  }
}

@container (max-width: 640px) {
  .task-form-grid,
  .quote-workbench {
    grid-template-columns: 1fr;
  }
  .field-control,
  .field-control--wide,
  .task-actions,
  .task-form-grid > :deep(.el-button) {
    grid-column: 1;
    width: 100%;
  }
  .quote-outcome {
    min-block-size: 0;
    padding-top: var(--space-3);
    padding-inline-start: 0;
    border-top: 1px solid var(--admin-line);
    border-inline-start: 0;
  }

  .quote-outcome {
    border-top-color: var(--admin-line);
  }

  .task-actions :deep(.el-button) {
    flex: 1 1 0;
  }
}

@media (max-width: 760px) {
  .marketing-page :deep(button),
  .marketing-page :deep(input),
  .marketing-page :deep(.el-input__wrapper),
  .marketing-page :deep(.el-input-number),
  .marketing-page :deep(.el-button) {
    min-height: var(--touch-target-min);
  }

  .marketing-page :deep(.el-button) {
    white-space: normal;
  }
}

@media (prefers-reduced-motion: reduce) {
  .marketing-tool,
  .task-result,
  .quote-outcome {
    transition: none;
  }
}
</style>

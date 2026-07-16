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
import PageHeader from '@/components/ui/PageHeader.vue'
import { useNotify } from '@/composables/useNotify'
import type { CouponWalletEntry, GroupBuyTeam, MarketingPriceQuote, SeckillOrder } from '@/types'
import { money } from '@/utils/format'

defineOptions({ name: 'MarketingView' })

type TaskKey = 'coupon' | 'quote' | 'seckill' | 'group'

const { t } = useI18n()
const notify = useNotify()
const couponId = ref(2400000000001)
const couponClaimKey = ref(`coupon-${Date.now()}`)
const couponCode = ref('')
const couponOrderId = ref<number | null>(null)
const quoteAmount = ref(128)
const quoteCouponCodes = ref('PLATFORM-20,SHOP-10')
const seckillActivityId = ref(2500000000001)
const seckillQuantity = ref(1)
const seckillOrderKey = ref(`flash-${Date.now()}`)
const groupActivityId = ref(2600000000001)
const groupTeamId = ref<number | null>(null)
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

function groupStatus(status: string): string {
  const labels: Record<string, string> = {
    OPEN: 'marketing.groupOpenStatus',
    SUCCEEDED: 'marketing.groupSucceededStatus',
    CANCELLED: 'marketing.groupCancelledStatus',
  }
  return t(labels[status] ?? 'marketing.statusUnavailable')
}

async function runClaimCoupon() {
  if (!validate('coupon', couponId.value > 0, 'marketing.positiveValueRequired')) return
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
      Boolean(couponCode.value.trim() && couponOrderId.value && couponOrderId.value > 0),
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
      Boolean(couponCode.value.trim() && couponOrderId.value && couponOrderId.value > 0),
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
      seckillActivityId.value > 0 && seckillQuantity.value > 0,
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
      groupActivityId.value > 0 && (groupTeamId.value === null || groupTeamId.value > 0),
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
      teamId: groupTeamId.value || undefined,
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
  <div class="route-view marketing-page">
    <PageHeader
      :eyebrow="t('nav.admin')"
      :title="t('marketing.title')"
      :description="t('marketing.description')"
    />

    <div class="marketing-task-grid">
      <section class="marketing-tool" aria-labelledby="coupon-task-title">
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
            ><el-input-number
              v-model="couponId"
              controls-position="right"
              :aria-label="t('marketing.couponId')"
            />
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
            ><el-input-number
              v-model="couponOrderId"
              controls-position="right"
              :aria-label="t('marketing.orderId')"
            />
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
            ><el-tag effect="plain">{{ couponStatus(latestCoupon.status) }}</el-tag>
          </div>
        </div>
      </section>

      <section class="marketing-tool" aria-labelledby="quote-task-title">
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

      <section class="marketing-tool" aria-labelledby="seckill-task-title">
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
            ><el-input-number
              v-model="seckillActivityId"
              controls-position="right"
              :aria-label="t('marketing.activityId')"
            />
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

      <section class="marketing-tool" aria-labelledby="group-task-title">
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
            ><el-input-number
              v-model="groupActivityId"
              controls-position="right"
              :aria-label="t('marketing.activityId')"
            />
          </div>
          <div class="field-control">
            <span>{{ t('marketing.teamId') }}</span
            ><el-input-number
              v-model="groupTeamId"
              controls-position="right"
              :aria-label="t('marketing.teamId')"
            />
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
            ><strong>{{
              t('marketing.groupSummary', {
                id: latestGroup.id,
                joined: latestGroup.joinedCount,
                target: latestGroup.targetSize,
                status: groupStatus(latestGroup.status),
              })
            }}</strong>
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
  min-width: 0;
}
.marketing-task-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  align-items: start;
}
.marketing-tool {
  display: grid;
  align-content: start;
  gap: var(--space-4);
  min-width: 0;
  padding: var(--space-4);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  background: var(--color-surface);
}
.tool-heading h2 {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  font-size: var(--text-lg);
}
.tool-heading p,
.field-control span,
.task-result > span,
.applied-coupons > span,
.quote-placeholder {
  margin: var(--space-1) 0 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
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
  gap: var(--space-1);
  min-width: 0;
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
}
.operation-divider {
  height: 1px;
  background: var(--color-line);
}
.task-feedback {
  display: grid;
  align-content: start;
  min-block-size: 2.5rem;
}
.task-error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}
.task-result {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-line);
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
  border-inline-start: 1px solid var(--color-line);
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
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}
.quote-totals dd {
  margin: 0;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.quote-total--payable {
  padding-top: var(--space-2);
  border-top: 1px solid var(--color-line);
}
.quote-total--payable dd {
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
  border-top: 1px solid var(--color-line);
}
.marketing-tool :deep(.el-button) {
  white-space: normal;
}
@media (max-width: 1000px) {
  .marketing-task-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 640px) {
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
    border-top: 1px solid var(--color-line);
    border-inline-start: 0;
  }
  .task-actions :deep(.el-button) {
    flex: 1 1 0;
  }
}
</style>

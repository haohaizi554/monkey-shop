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
import { couponStatusLabel, groupBuyStatusLabel, money } from '@/utils/format'

defineOptions({ name: 'MarketingView' })

type TaskKey = 'coupon' | 'quote' | 'seckill' | 'group'

const { t } = useI18n()
const notify = useNotify()
const couponId = ref(2400000000001)
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

async function runClaimCoupon() {
  if (!validate('coupon', couponId.value > 0, 'marketing.positiveValueRequired')) return
  const key = 'coupon:claim'
  setPending(key, true)
  try {
    latestCoupon.value = await claimCoupon({
      couponId: couponId.value,
      idempotencyKey: `coupon-${couponId.value}-${Date.now()}`,
    })
    couponCode.value = latestCoupon.value.couponCode
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
    notify.success(t('marketing.seckillAccepted'), { key: 'marketing:seckill' })
  } catch (error) {
    taskErrors.seckill = t('marketing.seckillFailed')
    notify.fromApiError(error, 'marketing.seckillFailed')
  } finally {
    setPending(key, false)
  }
}

async function runJoinGroup() {
  if (!validate('group', groupActivityId.value > 0, 'marketing.positiveValueRequired')) return
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
      <section class="marketing-tool" :aria-labelledby="'coupon-task-title'">
        <h2 id="coupon-task-title">
          <el-icon aria-hidden="true"><PriceTag /></el-icon>{{ t('marketing.coupon') }}
        </h2>
        <div class="task-form-grid">
          <div class="field-control">
            <span>{{ t('marketing.couponId') }}</span>
            <el-input-number
              v-model="couponId"
              :min="1"
              controls-position="right"
              :aria-label="t('marketing.couponId')"
            />
          </div>
          <el-button type="primary" :loading="isPending('coupon:claim')" @click="runClaimCoupon">
            {{ t('marketing.claim') }}
          </el-button>
          <div class="field-control">
            <span>{{ t('marketing.couponCode') }}</span>
            <el-input v-model="couponCode" :aria-label="t('marketing.couponCode')" />
          </div>
          <div class="field-control">
            <span>{{ t('marketing.orderId') }}</span>
            <el-input-number
              v-model="couponOrderId"
              :min="1"
              controls-position="right"
              :aria-label="t('marketing.orderId')"
            />
          </div>
          <div class="task-actions">
            <el-button :loading="isPending('coupon:redeem')" @click="runRedeemCoupon">
              {{ t('marketing.redeem') }}
            </el-button>
            <el-button
              :icon="RefreshRight"
              :loading="isPending('coupon:return')"
              @click="runReturnCoupon"
            >
              {{ t('marketing.return') }}
            </el-button>
          </div>
        </div>
        <p v-if="taskErrors.coupon" class="task-error" role="alert">{{ taskErrors.coupon }}</p>
        <div v-if="latestCoupon" class="task-result" role="status">
          <strong>{{ latestCoupon.couponCode }}</strong>
          <span>{{ couponStatusLabel(latestCoupon.status) }}</span>
        </div>
      </section>

      <section class="marketing-tool" :aria-labelledby="'quote-task-title'">
        <h2 id="quote-task-title">
          <el-icon aria-hidden="true"><PriceTag /></el-icon>{{ t('marketing.priceQuote') }}
        </h2>
        <div class="task-form-grid">
          <div class="field-control">
            <span>{{ t('marketing.orderAmount') }}</span>
            <el-input-number
              v-model="quoteAmount"
              :min="1"
              controls-position="right"
              :aria-label="t('marketing.orderAmount')"
            />
          </div>
          <div class="field-control field-control--wide">
            <span>{{ t('marketing.couponCodesHint') }}</span>
            <el-input v-model="quoteCouponCodes" :aria-label="t('marketing.couponCodesHint')" />
          </div>
          <el-button type="primary" :loading="isPending('quote')" @click="runQuote">
            {{ t('marketing.quote') }}
          </el-button>
        </div>
        <p v-if="taskErrors.quote" class="task-error" role="alert">{{ taskErrors.quote }}</p>
        <div v-if="latestQuote" class="quote-result" role="status">
          <span>{{
            t('marketing.originalAmount', { amount: money(latestQuote.originalAmount) })
          }}</span>
          <span>{{
            t('marketing.discountAmount', { amount: money(latestQuote.discountAmount) })
          }}</span>
          <strong>{{
            t('marketing.payableAmount', { amount: money(latestQuote.payableAmount) })
          }}</strong>
          <div class="applied-coupons">
            <span>{{ t('marketing.appliedCoupons') }}</span>
            <el-tag v-for="code in latestQuote.appliedCoupons" :key="code" effect="plain">{{
              code
            }}</el-tag>
          </div>
        </div>
      </section>

      <section class="marketing-tool" :aria-labelledby="'seckill-task-title'">
        <h2 id="seckill-task-title">
          <el-icon aria-hidden="true"><Lightning /></el-icon>{{ t('marketing.seckill') }}
        </h2>
        <div class="task-form-grid">
          <div class="field-control">
            <span>{{ t('marketing.activityId') }}</span>
            <el-input-number
              v-model="seckillActivityId"
              :min="1"
              controls-position="right"
              :aria-label="t('marketing.activityId')"
            />
          </div>
          <div class="field-control">
            <span>{{ t('marketing.quantity') }}</span>
            <el-input-number
              v-model="seckillQuantity"
              :min="1"
              controls-position="right"
              :aria-label="t('marketing.quantity')"
            />
          </div>
          <div class="field-control field-control--wide">
            <span>{{ t('marketing.idempotencyKey') }}</span>
            <el-input v-model="seckillOrderKey" :aria-label="t('marketing.idempotencyKey')" />
          </div>
          <el-button
            type="danger"
            :aria-label="t('marketing.submitSeckill')"
            :loading="isPending('seckill')"
            @click="runSeckill"
          >
            {{ t('marketing.submitSeckill') }}
          </el-button>
        </div>
        <p v-if="taskErrors.seckill" class="task-error" role="alert">{{ taskErrors.seckill }}</p>
        <div v-if="latestSeckill" class="task-result" role="status">
          {{ t('marketing.seckillOrder', { id: latestSeckill.id }) }}
        </div>
      </section>

      <section class="marketing-tool" :aria-labelledby="'group-task-title'">
        <h2 id="group-task-title">
          <el-icon aria-hidden="true"><UserFilled /></el-icon>{{ t('marketing.groupBuy') }}
        </h2>
        <div class="task-form-grid">
          <div class="field-control">
            <span>{{ t('marketing.activityId') }}</span>
            <el-input-number
              v-model="groupActivityId"
              :min="1"
              controls-position="right"
              :aria-label="t('marketing.activityId')"
            />
          </div>
          <div class="field-control">
            <span>{{ t('marketing.teamId') }}</span>
            <el-input-number
              v-model="groupTeamId"
              :min="1"
              controls-position="right"
              :aria-label="t('marketing.teamId')"
            />
          </div>
          <div class="field-control field-control--wide">
            <span>{{ t('marketing.idempotencyKey') }}</span>
            <el-input v-model="groupKey" :aria-label="t('marketing.idempotencyKey')" />
          </div>
          <el-button
            type="primary"
            :aria-label="t('marketing.joinGroupBuy')"
            :loading="isPending('group-buy')"
            @click="runJoinGroup"
          >
            {{ t('marketing.joinGroupBuy') }}
          </el-button>
        </div>
        <p v-if="taskErrors.group" class="task-error" role="alert">{{ taskErrors.group }}</p>
        <div v-if="latestGroup" class="task-result" role="status">
          {{
            t('marketing.groupSummary', {
              id: latestGroup.id,
              joined: latestGroup.joinedCount,
              target: latestGroup.targetSize,
              status: groupBuyStatusLabel(latestGroup.status),
            })
          }}
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

.marketing-tool h2 {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: 0;
  font-size: var(--text-lg);
}

.task-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: end;
  gap: var(--space-3);
}

.field-control {
  display: grid;
  gap: var(--space-1);
  min-width: 0;
}

.field-control span,
.applied-coupons > span {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.field-control--wide,
.task-actions,
.task-error,
.task-result,
.quote-result {
  grid-column: 1 / -1;
}

.field-control :deep(.el-input-number),
.field-control :deep(.el-input) {
  width: 100%;
}

.task-actions,
.applied-coupons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.task-error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}

.task-result,
.quote-result {
  padding-top: var(--space-3);
  border-top: 1px solid var(--color-line);
}

.task-result {
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
}

.quote-result {
  display: grid;
  gap: var(--space-2);
}

.applied-coupons {
  align-items: center;
  margin-top: var(--space-1);
}

@media (max-width: 1000px) {
  .marketing-task-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .task-form-grid {
    grid-template-columns: 1fr;
  }

  .field-control,
  .field-control--wide,
  .task-actions,
  .task-form-grid > :deep(.el-button) {
    grid-column: 1;
    width: 100%;
  }
}
</style>

<script setup lang="ts">
import { Lightning, PriceTag, RefreshRight, UserFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, ref } from 'vue'
import {
  claimCoupon,
  createSeckillOrder,
  joinGroupBuy,
  quoteMarketingPrice,
  redeemCoupon,
  returnCoupon,
} from '@/api/marketing'
import AppShell from '@/components/AppShell.vue'
import type { CouponWalletEntry, GroupBuyTeam, MarketingPriceQuote, SeckillOrder } from '@/types'

const userId = ref(1)
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
const busy = ref(false)
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

async function runClaimCoupon() {
  busy.value = true
  try {
    latestCoupon.value = await claimCoupon({
      couponId: couponId.value,
      userId: userId.value,
      idempotencyKey: `coupon-${userId.value}-${couponId.value}`,
    })
    couponCode.value = latestCoupon.value.couponCode
    ElMessage.success('Coupon claimed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to claim coupon')
  } finally {
    busy.value = false
  }
}

async function runRedeemCoupon() {
  if (!couponCode.value || !couponOrderId.value) {
    return
  }
  busy.value = true
  try {
    latestCoupon.value = await redeemCoupon({
      couponCode: couponCode.value,
      orderId: couponOrderId.value,
    })
    ElMessage.success('Coupon redeemed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to redeem coupon')
  } finally {
    busy.value = false
  }
}

async function runReturnCoupon() {
  if (!couponCode.value || !couponOrderId.value) {
    return
  }
  busy.value = true
  try {
    latestCoupon.value = await returnCoupon({
      couponCode: couponCode.value,
      orderId: couponOrderId.value,
    })
    ElMessage.success('Coupon returned')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to return coupon')
  } finally {
    busy.value = false
  }
}

async function runQuote() {
  busy.value = true
  try {
    latestQuote.value = await quoteMarketingPrice({
      orderAmount: quoteAmount.value,
      userId: userId.value,
      shopId: 1,
      couponCodes: parsedCouponCodes.value,
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to quote price')
  } finally {
    busy.value = false
  }
}

async function runSeckill() {
  busy.value = true
  try {
    latestSeckill.value = await createSeckillOrder({
      activityId: seckillActivityId.value,
      userId: userId.value,
      quantity: seckillQuantity.value,
      idempotencyKey: seckillOrderKey.value,
    })
    ElMessage.success('Seckill order accepted')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to create seckill order')
  } finally {
    busy.value = false
  }
}

async function runJoinGroup() {
  busy.value = true
  try {
    latestGroup.value = await joinGroupBuy({
      activityId: groupActivityId.value,
      userId: userId.value,
      teamId: groupTeamId.value || undefined,
      idempotencyKey: groupKey.value,
    })
    groupTeamId.value = latestGroup.value.id
    ElMessage.success('Group-buy updated')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to join group-buy')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AppShell>
    <section class="marketing-layout">
      <div class="marketing-toolbar">
        <el-input-number v-model="userId" :min="1" controls-position="right" />
        <el-tag type="info">User</el-tag>
      </div>

      <div class="marketing-grid">
        <section class="marketing-panel">
          <h2>
            <el-icon><PriceTag /></el-icon>
            Coupons
          </h2>
          <div class="field-row">
            <el-input-number v-model="couponId" :min="1" controls-position="right" />
            <el-button type="primary" :loading="busy" @click="runClaimCoupon">Claim</el-button>
          </div>
          <div class="field-row">
            <el-input v-model="couponCode" placeholder="Coupon code" />
            <el-input-number v-model="couponOrderId" :min="1" controls-position="right" />
          </div>
          <div class="field-row">
            <el-button :loading="busy" @click="runRedeemCoupon">Redeem</el-button>
            <el-button :icon="RefreshRight" :loading="busy" @click="runReturnCoupon">
              Return
            </el-button>
          </div>
          <el-alert
            v-if="latestCoupon"
            :closable="false"
            type="success"
            :title="`${latestCoupon.couponCode} ${latestCoupon.status}`"
          />
        </section>

        <section class="marketing-panel">
          <h2>
            <el-icon><PriceTag /></el-icon>
            Price
          </h2>
          <div class="field-row">
            <el-input-number v-model="quoteAmount" :min="1" controls-position="right" />
            <el-input v-model="quoteCouponCodes" placeholder="Coupon codes" />
            <el-button type="primary" :loading="busy" @click="runQuote">Quote</el-button>
          </div>
          <div v-if="latestQuote" class="metric-strip">
            <span>Original {{ latestQuote.originalAmount }}</span>
            <span>Discount {{ latestQuote.discountAmount }}</span>
            <strong>Payable {{ latestQuote.payableAmount }}</strong>
          </div>
        </section>

        <section class="marketing-panel">
          <h2>
            <el-icon><Lightning /></el-icon>
            Seckill
          </h2>
          <div class="field-row">
            <el-input-number v-model="seckillActivityId" :min="1" controls-position="right" />
            <el-input-number v-model="seckillQuantity" :min="1" controls-position="right" />
          </div>
          <div class="field-row">
            <el-input v-model="seckillOrderKey" placeholder="Idempotency key" />
            <el-button type="danger" :loading="busy" @click="runSeckill">Order</el-button>
          </div>
          <el-alert
            v-if="latestSeckill"
            :closable="false"
            type="success"
            :title="`Seckill order ${latestSeckill.id}`"
          />
        </section>

        <section class="marketing-panel">
          <h2>
            <el-icon><UserFilled /></el-icon>
            Group Buy
          </h2>
          <div class="field-row">
            <el-input-number v-model="groupActivityId" :min="1" controls-position="right" />
            <el-input-number v-model="groupTeamId" :min="1" controls-position="right" />
          </div>
          <div class="field-row">
            <el-input v-model="groupKey" placeholder="Idempotency key" />
            <el-button type="primary" :loading="busy" @click="runJoinGroup">Join</el-button>
          </div>
          <el-alert
            v-if="latestGroup"
            :closable="false"
            :type="latestGroup.status === 'SUCCEEDED' ? 'success' : 'info'"
            :title="`Team ${latestGroup.id}: ${latestGroup.joinedCount}/${latestGroup.targetSize} ${latestGroup.status}`"
          />
        </section>
      </div>
    </section>
  </AppShell>
</template>

<style scoped>
.marketing-layout {
  display: grid;
  gap: 16px;
}

.marketing-toolbar,
.field-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.marketing-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
}

.marketing-panel {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  display: grid;
  gap: 12px;
  padding: 16px;
}

.marketing-panel h2 {
  align-items: center;
  display: flex;
  font-size: 1rem;
  gap: 8px;
  margin: 0;
}

.field-row .el-input,
.field-row .el-input-number {
  max-width: 220px;
}

.metric-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
</style>

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
import { couponStatusLabel, groupBuyStatusLabel } from '@/utils/format'

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
    ElMessage.success('优惠券已领取')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '优惠券领取失败')
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
    ElMessage.success('优惠券已核销')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '优惠券核销失败')
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
    ElMessage.success('优惠券已退回')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '优惠券退回失败')
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
    ElMessage.error(error instanceof Error ? error.message : '价格试算失败')
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
    ElMessage.success('秒杀请求已受理')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '秒杀下单失败')
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
    ElMessage.success('拼团状态已更新')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '拼团加入失败')
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
        <el-tag type="info">用户编号</el-tag>
      </div>

      <div class="marketing-grid">
        <section class="marketing-panel">
          <h2>
            <el-icon><PriceTag /></el-icon>
            优惠券
          </h2>
          <div class="field-row">
            <el-input-number v-model="couponId" :min="1" controls-position="right" />
            <el-button type="primary" :loading="busy" @click="runClaimCoupon">领取</el-button>
          </div>
          <div class="field-row">
            <el-input v-model="couponCode" placeholder="优惠券码" />
            <el-input-number v-model="couponOrderId" :min="1" controls-position="right" />
          </div>
          <div class="field-row">
            <el-button :loading="busy" @click="runRedeemCoupon">核销</el-button>
            <el-button :icon="RefreshRight" :loading="busy" @click="runReturnCoupon">
              退回
            </el-button>
          </div>
          <el-alert
            v-if="latestCoupon"
            :closable="false"
            type="success"
            :title="`${latestCoupon.couponCode}：${couponStatusLabel(latestCoupon.status)}`"
          />
        </section>

        <section class="marketing-panel">
          <h2>
            <el-icon><PriceTag /></el-icon>
            价格试算
          </h2>
          <div class="field-row">
            <el-input-number v-model="quoteAmount" :min="1" controls-position="right" />
            <el-input v-model="quoteCouponCodes" placeholder="优惠券码，逗号分隔" />
            <el-button type="primary" :loading="busy" @click="runQuote">试算</el-button>
          </div>
          <div v-if="latestQuote" class="metric-strip">
            <span>原价 {{ latestQuote.originalAmount }}</span>
            <span>优惠 {{ latestQuote.discountAmount }}</span>
            <strong>应付 {{ latestQuote.payableAmount }}</strong>
          </div>
        </section>

        <section class="marketing-panel">
          <h2>
            <el-icon><Lightning /></el-icon>
            秒杀
          </h2>
          <div class="field-row">
            <el-input-number v-model="seckillActivityId" :min="1" controls-position="right" />
            <el-input-number v-model="seckillQuantity" :min="1" controls-position="right" />
          </div>
          <div class="field-row">
            <el-input v-model="seckillOrderKey" placeholder="幂等键" />
            <el-button type="danger" :loading="busy" @click="runSeckill">下单</el-button>
          </div>
          <el-alert
            v-if="latestSeckill"
            :closable="false"
            type="success"
            :title="`秒杀订单 ${latestSeckill.id}`"
          />
        </section>

        <section class="marketing-panel">
          <h2>
            <el-icon><UserFilled /></el-icon>
            拼团
          </h2>
          <div class="field-row">
            <el-input-number v-model="groupActivityId" :min="1" controls-position="right" />
            <el-input-number v-model="groupTeamId" :min="1" controls-position="right" />
          </div>
          <div class="field-row">
            <el-input v-model="groupKey" placeholder="幂等键" />
            <el-button type="primary" :loading="busy" @click="runJoinGroup">加入</el-button>
          </div>
          <el-alert
            v-if="latestGroup"
            :closable="false"
            :type="latestGroup.status === 'SUCCEEDED' ? 'success' : 'info'"
            :title="`团 ${latestGroup.id}：${latestGroup.joinedCount}/${latestGroup.targetSize}，${groupBuyStatusLabel(latestGroup.status)}`"
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

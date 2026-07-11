<script setup lang="ts">
import { Check, Refresh, Star, Tickets } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import * as membershipApi from '@/api/membership'
import type { MembershipDashboard, MembershipLevel } from '@/types'
import { couponStatusLabel, dateTime, membershipLevelLabel } from '@/utils/format'

const dashboard = ref<MembershipDashboard>()
const loading = ref(false)
const levels: MembershipLevel[] = ['BASIC', 'SILVER', 'GOLD', 'DIAMOND']
const identityForm = reactive({ realName: '', idCardNo: '' })
const earnForm = reactive({ amount: 199, orderId: undefined as number | undefined })
const redeemForm = reactive({ points: 100 })
const levelForm = reactive({ level: 'SILVER' as MembershipLevel, reason: 'manual', totpCode: '' })
const collectionForm = reactive({ productId: 1, targetPrice: 99 })
const browseForm = reactive({ productId: 1 })

const profile = computed(() => dashboard.value?.profile)
const wallet = computed(() => dashboard.value?.wallet)
const progress = computed(() => {
  const value = profile.value?.growthValue ?? 0
  const current = profile.value?.level ?? 'BASIC'
  const index = levels.indexOf(current)
  const next = levels[index + 1]
  if (!next) {
    return 100
  }
  const floor = levelMin(current)
  const ceiling = levelMin(next)
  return Math.min(100, Math.round(((value - floor) / (ceiling - floor)) * 100))
})

function levelMin(level: MembershipLevel): number {
  return { BASIC: 0, SILVER: 1000, GOLD: 5000, DIAMOND: 20000 }[level]
}

function levelLabel(level: MembershipLevel | string): string {
  return membershipLevelLabel(level)
}

async function loadDashboard() {
  loading.value = true
  try {
    dashboard.value = await membershipApi.membershipDashboard()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法加载会员数据')
  } finally {
    loading.value = false
  }
}

async function checkIn() {
  const result = await membershipApi.checkIn()
  ElMessage.success(`已签到，+${result.rewardPoints} 积分`)
  await loadDashboard()
}

async function verifyIdentity() {
  dashboard.value = await membershipApi.verifyIdentity(identityForm)
  Object.assign(identityForm, { realName: '', idCardNo: '' })
  ElMessage.success('实名信息已验证')
}

async function earnPoints() {
  await membershipApi.earnPoints({
    amount: earnForm.amount,
    orderId: earnForm.orderId,
    referenceKey: earnForm.orderId ? `order:${earnForm.orderId}` : 'manual-purchase',
  })
  ElMessage.success('积分已入账')
  await loadDashboard()
}

async function redeemPoints() {
  await membershipApi.redeemPoints({ points: redeemForm.points, referenceKey: 'wallet-redemption' })
  ElMessage.success('积分已兑换')
  await loadDashboard()
}

async function changeLevel() {
  dashboard.value = await membershipApi.changeLevel(levelForm)
  levelForm.totpCode = ''
  ElMessage.success('会员等级已更新')
}

async function addCollection() {
  await membershipApi.addCollection(collectionForm)
  ElMessage.success('已加入关注')
  await loadDashboard()
}

async function removeCollection(productId: number) {
  await ElMessageBox.confirm('移除这个价格关注？', '确认操作', { type: 'warning' })
  await membershipApi.removeCollection(productId)
  await loadDashboard()
}

async function recordBrowse() {
  await membershipApi.recordBrowse(browseForm)
  ElMessage.success('浏览记录已写入')
  await loadDashboard()
}

async function scanPriceDrops() {
  const result = await membershipApi.scanPriceDrops()
  ElMessage.success(`已扫描 ${result.scanned} 条，提醒 ${result.reminders} 条`)
  await loadDashboard()
}

onMounted(() => {
  void loadDashboard()
})
</script>

<template>
  <div class="route-view">
    <section v-loading="loading" class="membership-page">
      <header class="membership-header">
        <div>
          <p class="eyebrow">会员中心</p>
          <h1>{{ levelLabel(profile?.level || 'BASIC') }}</h1>
        </div>
        <el-button type="primary" :icon="Check" @click="checkIn">每日签到</el-button>
      </header>

      <section class="metrics-row">
        <div class="metric-panel">
          <span>成长值</span>
          <strong>{{ profile?.growthValue ?? 0 }}</strong>
          <el-progress :percentage="progress" :stroke-width="10" />
        </div>
        <div class="metric-panel">
          <span>积分余额</span>
          <strong>{{ wallet?.balance ?? 0 }}</strong>
          <small>约 CNY {{ wallet?.moneyEquivalent ?? 0 }}</small>
        </div>
        <div class="metric-panel">
          <span>累计获得</span>
          <strong>{{ wallet?.totalEarned ?? 0 }}</strong>
          <small>已使用 {{ wallet?.totalSpent ?? 0 }}</small>
        </div>
      </section>

      <section class="section-grid">
        <section class="tool-panel">
          <h2>实名状态</h2>
          <p>
            {{
              profile?.verified ? `${profile.maskedRealName} / ${profile.maskedIdCardNo}` : '未认证'
            }}
          </p>
          <div class="compact-form">
            <el-input v-model="identityForm.realName" placeholder="真实姓名" />
            <el-input v-model="identityForm.idCardNo" placeholder="身份证号" />
            <el-button type="primary" @click="verifyIdentity">认证</el-button>
          </div>
        </section>

        <section class="tool-panel">
          <h2>积分账户</h2>
          <div class="compact-form split">
            <el-input-number v-model="earnForm.amount" :min="1" :step="10" />
            <el-input-number v-model="earnForm.orderId" :min="1" placeholder="订单 ID" />
            <el-button :icon="Tickets" @click="earnPoints">入账</el-button>
            <el-input-number v-model="redeemForm.points" :min="1" :step="100" />
            <el-button type="warning" @click="redeemPoints">兑换</el-button>
          </div>
        </section>

        <section class="tool-panel">
          <h2>等级调整</h2>
          <div class="compact-form">
            <el-select v-model="levelForm.level">
              <el-option
                v-for="level in levels"
                :key="level"
                :label="levelLabel(level)"
                :value="level"
              />
            </el-select>
            <el-input v-model="levelForm.reason" placeholder="调整原因" />
            <el-input v-model="levelForm.totpCode" placeholder="管理员动态码" />
            <el-button :icon="Star" @click="changeLevel">调整</el-button>
          </div>
        </section>

        <section class="tool-panel">
          <h2>价格关注</h2>
          <div class="compact-form">
            <el-input-number v-model="collectionForm.productId" :min="1" />
            <el-input-number v-model="collectionForm.targetPrice" :min="0" :step="10" />
            <el-button type="primary" @click="addCollection">保存</el-button>
            <el-button :icon="Refresh" @click="scanPriceDrops">扫描降价</el-button>
          </div>
        </section>
      </section>

      <section class="table-band">
        <div class="band-title">
          <h2>优惠券账户</h2>
        </div>
        <el-table :data="dashboard?.coupons || []" class="data-table">
          <el-table-column prop="couponCode" label="券码" />
          <el-table-column label="状态" width="140">
            <template #default="{ row }">
              <el-tag disable-transitions>{{ couponStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="领取时间" width="220">
            <template #default="{ row }">{{ dateTime(row.claimedAt) }}</template>
          </el-table-column>
        </el-table>
      </section>

      <section class="table-band">
        <div class="band-title">
          <h2>价格关注</h2>
        </div>
        <el-table :data="dashboard?.collections || []" class="data-table">
          <el-table-column prop="productName" label="商品" />
          <el-table-column prop="lastPrice" label="最近价格" width="140" />
          <el-table-column prop="targetPrice" label="目标价" width="140" />
          <el-table-column label="已提醒" width="120">
            <template #default="{ row }">
              {{ row.priceDropNotified ? '已提醒' : '未提醒' }}
            </template>
          </el-table-column>
          <el-table-column width="120">
            <template #default="{ row }">
              <el-button type="danger" plain @click="removeCollection(row.productId)">
                移除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="table-band">
        <div class="band-title">
          <h2>浏览历史</h2>
          <div class="inline-actions">
            <el-input-number v-model="browseForm.productId" :min="1" />
            <el-button @click="recordBrowse">记录</el-button>
          </div>
        </div>
        <el-table :data="dashboard?.browseHistory || []" class="data-table">
          <el-table-column prop="productName" label="商品" />
          <el-table-column label="浏览时间" width="220">
            <template #default="{ row }">{{ dateTime(row.viewedAt) }}</template>
          </el-table-column>
          <el-table-column label="过期时间" width="220">
            <template #default="{ row }">{{ dateTime(row.expiresAt) }}</template>
          </el-table-column>
        </el-table>
      </section>
    </section>
  </div>
</template>

<style scoped>
.membership-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.membership-header,
.band-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.eyebrow {
  margin: 0 0 4px;
  color: var(--text-muted);
  font-size: 0.85rem;
  text-transform: uppercase;
}

h1,
h2,
p {
  margin: 0;
}

.metrics-row,
.section-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.metric-panel,
.tool-panel,
.table-band {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  padding: 16px;
}

.metric-panel {
  display: flex;
  min-height: 120px;
  flex-direction: column;
  justify-content: space-between;
}

.metric-panel span,
.metric-panel small {
  color: var(--text-muted);
}

.metric-panel strong {
  font-size: 2rem;
}

.tool-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.compact-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 10px;
}

.compact-form.split {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.inline-actions {
  display: flex;
  gap: 10px;
}

.data-table {
  width: 100%;
  margin-top: 12px;
}

@media (max-width: 720px) {
  .membership-header,
  .band-title,
  .inline-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .compact-form.split {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

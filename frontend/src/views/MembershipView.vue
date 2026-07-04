<script setup lang="ts">
import { Check, Refresh, Star, Tickets } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import * as membershipApi from '@/api/membership'
import AppShell from '@/components/AppShell.vue'
import type { MembershipDashboard, MembershipLevel } from '@/types'

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

async function loadDashboard() {
  loading.value = true
  try {
    dashboard.value = await membershipApi.membershipDashboard()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load membership')
  } finally {
    loading.value = false
  }
}

async function checkIn() {
  const result = await membershipApi.checkIn()
  ElMessage.success(`+${result.rewardPoints} points`)
  await loadDashboard()
}

async function verifyIdentity() {
  dashboard.value = await membershipApi.verifyIdentity(identityForm)
  Object.assign(identityForm, { realName: '', idCardNo: '' })
  ElMessage.success('Verified')
}

async function earnPoints() {
  await membershipApi.earnPoints({
    amount: earnForm.amount,
    orderId: earnForm.orderId,
    referenceKey: earnForm.orderId ? `order:${earnForm.orderId}` : 'manual-purchase',
  })
  ElMessage.success('Points posted')
  await loadDashboard()
}

async function redeemPoints() {
  await membershipApi.redeemPoints({ points: redeemForm.points, referenceKey: 'wallet-redemption' })
  ElMessage.success('Points redeemed')
  await loadDashboard()
}

async function changeLevel() {
  dashboard.value = await membershipApi.changeLevel(levelForm)
  levelForm.totpCode = ''
  ElMessage.success('Level updated')
}

async function addCollection() {
  await membershipApi.addCollection(collectionForm)
  ElMessage.success('Saved')
  await loadDashboard()
}

async function removeCollection(productId: number) {
  await ElMessageBox.confirm('Remove this collection?', 'Confirm', { type: 'warning' })
  await membershipApi.removeCollection(productId)
  await loadDashboard()
}

async function recordBrowse() {
  await membershipApi.recordBrowse(browseForm)
  ElMessage.success('Recorded')
  await loadDashboard()
}

async function scanPriceDrops() {
  const result = await membershipApi.scanPriceDrops()
  ElMessage.success(`${result.scanned} scanned, ${result.reminders} reminders`)
  await loadDashboard()
}

onMounted(() => {
  void loadDashboard()
})
</script>

<template>
  <AppShell>
    <section v-loading="loading" class="membership-page">
      <header class="membership-header">
        <div>
          <p class="eyebrow">Membership</p>
          <h1>{{ profile?.level || 'BASIC' }}</h1>
        </div>
        <el-button type="primary" :icon="Check" @click="checkIn">Check in</el-button>
      </header>

      <section class="metrics-row">
        <div class="metric-panel">
          <span>Growth</span>
          <strong>{{ profile?.growthValue ?? 0 }}</strong>
          <el-progress :percentage="progress" :stroke-width="10" />
        </div>
        <div class="metric-panel">
          <span>Points</span>
          <strong>{{ wallet?.balance ?? 0 }}</strong>
          <small>¥{{ wallet?.moneyEquivalent ?? 0 }}</small>
        </div>
        <div class="metric-panel">
          <span>Earned</span>
          <strong>{{ wallet?.totalEarned ?? 0 }}</strong>
          <small>Spent {{ wallet?.totalSpent ?? 0 }}</small>
        </div>
      </section>

      <section class="section-grid">
        <section class="tool-panel">
          <h2>Verified Identity</h2>
          <p>
            {{
              profile?.verified
                ? `${profile.maskedRealName} · ${profile.maskedIdCardNo}`
                : 'Unverified'
            }}
          </p>
          <div class="compact-form">
            <el-input v-model="identityForm.realName" placeholder="Real name" />
            <el-input v-model="identityForm.idCardNo" placeholder="Identity number" />
            <el-button type="primary" @click="verifyIdentity">Verify</el-button>
          </div>
        </section>

        <section class="tool-panel">
          <h2>Points Wallet</h2>
          <div class="compact-form split">
            <el-input-number v-model="earnForm.amount" :min="1" :step="10" />
            <el-input-number v-model="earnForm.orderId" :min="1" placeholder="Order" />
            <el-button :icon="Tickets" @click="earnPoints">Post</el-button>
            <el-input-number v-model="redeemForm.points" :min="1" :step="100" />
            <el-button type="warning" @click="redeemPoints">Redeem</el-button>
          </div>
        </section>

        <section class="tool-panel">
          <h2>Level</h2>
          <div class="compact-form">
            <el-select v-model="levelForm.level">
              <el-option v-for="level in levels" :key="level" :label="level" :value="level" />
            </el-select>
            <el-input v-model="levelForm.reason" placeholder="Reason" />
            <el-input v-model="levelForm.totpCode" placeholder="TOTP" />
            <el-button :icon="Star" @click="changeLevel">Change</el-button>
          </div>
        </section>

        <section class="tool-panel">
          <h2>Collections</h2>
          <div class="compact-form">
            <el-input-number v-model="collectionForm.productId" :min="1" />
            <el-input-number v-model="collectionForm.targetPrice" :min="0" :step="10" />
            <el-button type="primary" @click="addCollection">Save</el-button>
            <el-button :icon="Refresh" @click="scanPriceDrops">Scan</el-button>
          </div>
        </section>
      </section>

      <section class="table-band">
        <div class="band-title">
          <h2>Coupon Account</h2>
        </div>
        <el-table :data="dashboard?.coupons || []" class="data-table">
          <el-table-column prop="couponCode" label="Code" />
          <el-table-column prop="status" label="Status" width="140" />
          <el-table-column prop="claimedAt" label="Claimed" width="220" />
        </el-table>
      </section>

      <section class="table-band">
        <div class="band-title">
          <h2>Collections</h2>
        </div>
        <el-table :data="dashboard?.collections || []" class="data-table">
          <el-table-column prop="productName" label="Product" />
          <el-table-column prop="lastPrice" label="Last price" width="140" />
          <el-table-column prop="targetPrice" label="Target" width="140" />
          <el-table-column prop="priceDropNotified" label="Notified" width="120" />
          <el-table-column width="120">
            <template #default="{ row }">
              <el-button type="danger" plain @click="removeCollection(row.productId)"
                >Remove</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="table-band">
        <div class="band-title">
          <h2>Browse History</h2>
          <div class="inline-actions">
            <el-input-number v-model="browseForm.productId" :min="1" />
            <el-button @click="recordBrowse">Record</el-button>
          </div>
        </div>
        <el-table :data="dashboard?.browseHistory || []" class="data-table">
          <el-table-column prop="productName" label="Product" />
          <el-table-column prop="viewedAt" label="Viewed" width="220" />
          <el-table-column prop="expiresAt" label="Expires" width="220" />
        </el-table>
      </section>
    </section>
  </AppShell>
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
  color: var(--color-text-muted);
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
  color: var(--color-text-muted);
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

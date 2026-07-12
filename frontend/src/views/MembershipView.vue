<script setup lang="ts">
import { Check, Refresh, Star, Tickets } from '@element-plus/icons-vue'
import { computed, onMounted, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import * as membershipApi from '@/api/membership'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import { useAuthStore } from '@/stores/auth'
import type { MembershipDashboard, MembershipLevel } from '@/types'
import { couponStatusLabel, dateTime, membershipLevelLabel } from '@/utils/format'

type MembershipMutation =
  | 'checkIn'
  | 'verifyIdentity'
  | 'earnPoints'
  | 'redeemPoints'
  | 'changeLevel'
  | 'addCollection'
  | 'recordBrowse'
  | 'scanPriceDrops'

const auth = useAuthStore()
const notify = useNotify()
const { t } = useI18n()
const {
  data: dashboard,
  status,
  error,
  load,
} = useAsyncState<MembershipDashboard>({ timeoutMs: 20000 })
const levels: MembershipLevel[] = ['BASIC', 'SILVER', 'GOLD', 'DIAMOND']
const identityForm = reactive({ realName: '', idCardNo: '' })
const earnForm = reactive({ amount: 199, orderId: undefined as number | undefined })
const redeemForm = reactive({ points: 100 })
const levelForm = reactive({ level: 'SILVER' as MembershipLevel, reason: 'manual', totpCode: '' })
const collectionForm = reactive({ productId: 1, targetPrice: 99 })
const browseForm = reactive({ productId: 1 })
const pending = reactive<Record<MembershipMutation, boolean>>({
  checkIn: false,
  verifyIdentity: false,
  earnPoints: false,
  redeemPoints: false,
  changeLevel: false,
  addCollection: false,
  recordBrowse: false,
  scanPriceDrops: false,
})
const removingCollectionIds = reactive(new Set<number>())

const profile = computed(() => dashboard.value?.profile)
const wallet = computed(() => dashboard.value?.wallet)
const coupons = computed(() => dashboard.value?.coupons ?? [])
const collections = computed(() => dashboard.value?.collections ?? [])
const browseHistory = computed(() => dashboard.value?.browseHistory ?? [])
const identitySummary = computed(() => {
  if (!profile.value?.verified) {
    return t('membership.notVerified')
  }
  const maskedValues = [profile.value.maskedRealName, profile.value.maskedIdCardNo].filter(Boolean)
  return maskedValues.length ? maskedValues.join(' / ') : t('membership.verified')
})
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
  return Math.max(0, Math.min(100, Math.round(((value - floor) / (ceiling - floor)) * 100)))
})

function levelMin(level: MembershipLevel): number {
  return { BASIC: 0, SILVER: 1000, GOLD: 5000, DIAMOND: 20000 }[level]
}

function levelLabel(level: MembershipLevel | string): string {
  return membershipLevelLabel(level)
}

async function loadDashboard() {
  await load(() => membershipApi.membershipDashboard())
}

async function runMutation(key: MembershipMutation, mutation: () => Promise<void>) {
  if (pending[key]) {
    return
  }
  pending[key] = true
  try {
    await mutation()
  } catch (caught) {
    notify.fromApiError(caught, 'membership.actionFailed')
  } finally {
    pending[key] = false
  }
}

async function checkIn() {
  await runMutation('checkIn', async () => {
    const result = await membershipApi.checkIn()
    notify.success(t('membership.checkedIn', { points: result.rewardPoints }))
    await loadDashboard()
  })
}

async function verifyIdentity() {
  await runMutation('verifyIdentity', async () => {
    dashboard.value = await membershipApi.verifyIdentity(identityForm)
    Object.assign(identityForm, { realName: '', idCardNo: '' })
    notify.success(t('membership.verified'))
  })
}

async function earnPoints() {
  await runMutation('earnPoints', async () => {
    await membershipApi.earnPoints({
      amount: earnForm.amount,
      orderId: earnForm.orderId,
      referenceKey: earnForm.orderId ? `order:${earnForm.orderId}` : 'manual-purchase',
    })
    notify.success(t('membership.earned'))
    await loadDashboard()
  })
}

async function redeemPoints() {
  await runMutation('redeemPoints', async () => {
    await membershipApi.redeemPoints({
      points: redeemForm.points,
      referenceKey: 'wallet-redemption',
    })
    notify.success(t('membership.redeemed'))
    await loadDashboard()
  })
}

async function changeLevel() {
  await runMutation('changeLevel', async () => {
    dashboard.value = await membershipApi.changeLevel(levelForm)
    levelForm.totpCode = ''
    notify.success(t('membership.levelUpdated'))
  })
}

async function addCollection() {
  await runMutation('addCollection', async () => {
    await membershipApi.addCollection(collectionForm)
    notify.success(t('membership.collectionAdded'))
    await loadDashboard()
  })
}

async function removeCollection(productId: number) {
  const confirmed = await notify.confirm({
    title: t('common.confirm'),
    content: t('membership.removeConfirm'),
    type: 'warning',
  })
  if (!confirmed || removingCollectionIds.has(productId)) {
    return
  }

  removingCollectionIds.add(productId)
  try {
    await membershipApi.removeCollection(productId)
    notify.success(t('common.updated'))
    await loadDashboard()
  } catch (caught) {
    notify.fromApiError(caught, 'membership.actionFailed')
  } finally {
    removingCollectionIds.delete(productId)
  }
}

async function recordBrowse() {
  await runMutation('recordBrowse', async () => {
    await membershipApi.recordBrowse(browseForm)
    notify.success(t('membership.browseRecorded'))
    await loadDashboard()
  })
}

async function scanPriceDrops() {
  await runMutation('scanPriceDrops', async () => {
    const result = await membershipApi.scanPriceDrops()
    notify.success(
      t('membership.scanResult', { scanned: result.scanned, reminders: result.reminders }),
    )
    await loadDashboard()
  })
}

onMounted(() => {
  void loadDashboard()
})
</script>

<template>
  <div class="route-view membership-page">
    <PageHeader :title="$t('membership.center')" :eyebrow="levelLabel(profile?.level || 'BASIC')" />

    <AsyncStateView :status="status" :error="error" @retry="loadDashboard">
      <section class="account-summary" :aria-label="$t('membership.center')">
        <div class="account-summary__level">
          <span>{{ $t('membership.growthValue') }}</span>
          <strong>{{ levelLabel(profile?.level || 'BASIC') }}</strong>
          <el-progress
            :percentage="progress"
            :stroke-width="8"
            :aria-label="$t('membership.growthValue')"
          />
        </div>
        <dl class="account-summary__metrics">
          <div>
            <dt>{{ $t('membership.pointsBalance') }}</dt>
            <dd>
              {{ wallet?.balance ?? 0 }}
              <small>{{
                $t('membership.moneyEquivalent', { amount: wallet?.moneyEquivalent ?? 0 })
              }}</small>
            </dd>
          </div>
          <div>
            <dt>{{ $t('membership.totalEarned') }}</dt>
            <dd>
              {{ wallet?.totalEarned ?? 0 }}
              <small>{{ $t('membership.totalSpent', { spent: wallet?.totalSpent ?? 0 }) }}</small>
            </dd>
          </div>
        </dl>
      </section>

      <section class="task-section" data-membership-section="identity">
        <div class="task-heading">
          <div>
            <h2>{{ $t('membership.identityStatus') }}</h2>
            <p>{{ identitySummary }}</p>
          </div>
          <el-button
            type="primary"
            :icon="Check"
            :loading="pending.checkIn"
            :disabled="pending.checkIn"
            @click="checkIn"
          >
            {{ $t('membership.checkIn') }}
          </el-button>
        </div>

        <div class="compact-form identity-form">
          <el-input
            v-model="identityForm.realName"
            :aria-label="$t('membership.realName')"
            :placeholder="$t('membership.realName')"
            autocomplete="off"
          />
          <el-input
            v-model="identityForm.idCardNo"
            :aria-label="$t('membership.idCardNo')"
            :placeholder="$t('membership.idCardNo')"
            type="password"
            autocomplete="off"
          />
          <el-button
            type="primary"
            :loading="pending.verifyIdentity"
            :disabled="pending.verifyIdentity"
            @click="verifyIdentity"
          >
            {{ $t('membership.verify') }}
          </el-button>
        </div>

        <div v-if="auth.isAdmin" class="admin-tools">
          <h3>{{ $t('membership.levelAdjust') }}</h3>
          <div class="compact-form level-form">
            <el-select v-model="levelForm.level" :aria-label="$t('membership.levelAdjust')">
              <el-option
                v-for="level in levels"
                :key="level"
                :label="levelLabel(level)"
                :value="level"
              />
            </el-select>
            <el-input v-model="levelForm.reason" :placeholder="$t('membership.adjustReason')" />
            <el-input
              v-model="levelForm.totpCode"
              :placeholder="$t('membership.adminTotp')"
              type="password"
              autocomplete="one-time-code"
            />
            <el-button
              :icon="Star"
              :loading="pending.changeLevel"
              :disabled="pending.changeLevel"
              @click="changeLevel"
            >
              {{ $t('membership.adjust') }}
            </el-button>
          </div>
        </div>
      </section>

      <section class="task-section" data-membership-section="points">
        <div class="task-heading">
          <h2>{{ $t('membership.pointsAccount') }}</h2>
        </div>
        <div class="points-actions">
          <div v-if="auth.isAdmin" class="compact-form points-form">
            <el-input-number
              v-model="earnForm.amount"
              :aria-label="$t('membership.totalEarned')"
              :min="1"
              :step="10"
            />
            <el-input-number
              v-model="earnForm.orderId"
              :aria-label="$t('membership.orderId')"
              :min="1"
              :placeholder="$t('membership.orderId')"
            />
            <el-button
              :icon="Tickets"
              :loading="pending.earnPoints"
              :disabled="pending.earnPoints"
              @click="earnPoints"
            >
              {{ $t('membership.earn') }}
            </el-button>
          </div>
          <div class="compact-form redeem-form">
            <el-input-number
              v-model="redeemForm.points"
              :aria-label="$t('membership.pointsBalance')"
              :min="1"
              :step="100"
            />
            <el-button
              type="warning"
              :loading="pending.redeemPoints"
              :disabled="pending.redeemPoints"
              @click="redeemPoints"
            >
              {{ $t('membership.redeem') }}
            </el-button>
          </div>
        </div>
      </section>

      <section class="task-section" data-membership-section="price-watch">
        <div class="task-heading">
          <h2>{{ $t('membership.priceWatch') }}</h2>
          <el-button
            v-if="auth.isAdmin"
            :icon="Refresh"
            :loading="pending.scanPriceDrops"
            :disabled="pending.scanPriceDrops"
            @click="scanPriceDrops"
          >
            {{ $t('membership.scanPriceDrops') }}
          </el-button>
        </div>
        <div class="compact-form collection-form">
          <el-input-number
            v-model="collectionForm.productId"
            :aria-label="$t('common.product')"
            :min="1"
          />
          <el-input-number
            v-model="collectionForm.targetPrice"
            :aria-label="$t('membership.targetPrice')"
            :min="0"
            :step="10"
          />
          <el-button
            type="primary"
            :loading="pending.addCollection"
            :disabled="pending.addCollection"
            @click="addCollection"
          >
            {{ $t('common.save') }}
          </el-button>
        </div>

        <DataTableShell :aria-label="$t('membership.priceWatch')" :empty="collections.length === 0">
          <template #empty>{{ $t('common.noData') }}</template>
          <el-table :data="collections" class="data-table">
            <el-table-column prop="productName" :label="$t('common.product')" min-width="180" />
            <el-table-column prop="lastPrice" :label="$t('membership.lastPrice')" width="140" />
            <el-table-column prop="targetPrice" :label="$t('membership.targetPrice')" width="140" />
            <el-table-column :label="$t('membership.notified')" width="120">
              <template #default="{ row }">
                {{
                  row.priceDropNotified ? $t('membership.notifiedYes') : $t('membership.notifiedNo')
                }}
              </template>
            </el-table-column>
            <el-table-column width="120">
              <template #default="{ row }">
                <el-button
                  type="danger"
                  plain
                  :loading="removingCollectionIds.has(row.productId)"
                  :disabled="removingCollectionIds.has(row.productId)"
                  @click="removeCollection(row.productId)"
                >
                  {{ $t('common.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </section>

      <section class="task-section" data-membership-section="coupons">
        <div class="task-heading">
          <h2>{{ $t('membership.couponAccount') }}</h2>
        </div>
        <DataTableShell :aria-label="$t('membership.couponAccount')" :empty="coupons.length === 0">
          <template #empty>{{ $t('common.noData') }}</template>
          <el-table :data="coupons" class="data-table">
            <el-table-column
              prop="couponCode"
              :label="$t('membership.couponCode')"
              min-width="180"
            />
            <el-table-column :label="$t('common.status')" width="140">
              <template #default="{ row }">
                <el-tag disable-transitions>{{ couponStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="$t('membership.claimedAt')" width="220">
              <template #default="{ row }">{{ dateTime(row.claimedAt) }}</template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </section>

      <section class="task-section" data-membership-section="history">
        <div class="task-heading">
          <h2>{{ $t('membership.browseHistory') }}</h2>
          <div class="history-action">
            <el-input-number
              v-model="browseForm.productId"
              :aria-label="$t('common.product')"
              :min="1"
            />
            <el-button
              :loading="pending.recordBrowse"
              :disabled="pending.recordBrowse"
              @click="recordBrowse"
            >
              {{ $t('membership.record') }}
            </el-button>
          </div>
        </div>
        <DataTableShell
          :aria-label="$t('membership.browseHistory')"
          :empty="browseHistory.length === 0"
        >
          <template #empty>{{ $t('common.noData') }}</template>
          <el-table :data="browseHistory" class="data-table">
            <el-table-column prop="productName" :label="$t('common.product')" min-width="180" />
            <el-table-column :label="$t('membership.viewedAt')" width="220">
              <template #default="{ row }">{{ dateTime(row.viewedAt) }}</template>
            </el-table-column>
            <el-table-column :label="$t('membership.expiresAt')" width="220">
              <template #default="{ row }">{{ dateTime(row.expiresAt) }}</template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </section>
    </AsyncStateView>
  </div>
</template>

<style scoped>
.membership-page {
  display: grid;
  gap: 0;
}

.account-summary {
  display: grid;
  grid-template-columns: minmax(220px, 0.8fr) minmax(320px, 1.2fr);
  gap: 24px;
  align-items: center;
  padding: 18px 20px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-surface);
  background: var(--color-surface);
}

.account-summary__level {
  display: grid;
  gap: 8px;
}

.account-summary__level span,
.account-summary__metrics dt,
.account-summary__metrics small,
.task-heading p {
  color: var(--text-muted);
}

.account-summary__level strong {
  font-size: 1.35rem;
}

.account-summary__metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin: 0;
}

.account-summary__metrics div {
  display: grid;
  gap: 4px;
}

.account-summary__metrics dd {
  display: grid;
  gap: 4px;
  margin: 0;
  font-size: 1.5rem;
  font-weight: 700;
}

.account-summary__metrics dd small {
  font-size: var(--text-sm);
  font-weight: 400;
}

.task-section {
  display: grid;
  gap: 16px;
  padding: 24px 0;
  border-bottom: 1px solid var(--color-border);
}

.task-section:last-child {
  border-bottom: 0;
}

.task-heading {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
}

.task-heading h2,
.task-heading p,
.admin-tools h3 {
  margin: 0;
}

.task-heading h2 {
  font-size: 1.1rem;
}

.task-heading p {
  margin-top: 4px;
}

.compact-form,
.points-actions {
  display: grid;
  gap: 12px;
}

.identity-form {
  grid-template-columns: minmax(160px, 1fr) minmax(220px, 1.4fr) auto;
}

.level-form {
  grid-template-columns: 180px minmax(180px, 1fr) minmax(180px, 1fr) auto;
}

.points-actions {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.points-form {
  grid-template-columns: repeat(2, minmax(0, 1fr)) auto;
}

.redeem-form {
  grid-template-columns: minmax(0, 1fr) auto;
}

.collection-form {
  grid-template-columns: repeat(2, minmax(0, 220px)) auto;
  justify-content: start;
}

.admin-tools {
  display: grid;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
}

.history-action {
  display: flex;
  gap: 10px;
  align-items: center;
}

.data-table {
  width: 100%;
}

@media (max-width: 900px) {
  .account-summary,
  .points-actions {
    grid-template-columns: minmax(0, 1fr);
  }

  .identity-form,
  .level-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .account-summary__metrics,
  .identity-form,
  .level-form,
  .points-form,
  .redeem-form,
  .collection-form {
    grid-template-columns: minmax(0, 1fr);
  }

  .task-heading,
  .history-action {
    align-items: stretch;
    flex-direction: column;
  }

  .task-heading :deep(.el-button),
  .history-action :deep(.el-button),
  .compact-form :deep(.el-button) {
    min-height: 44px;
  }
}
</style>

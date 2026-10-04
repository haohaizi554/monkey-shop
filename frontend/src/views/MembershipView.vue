<script setup lang="ts">
import { Check, RefreshRight, Star, Tickets } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import * as membershipApi from '@/api/membership'
import MascotState from '@/components/mascot/MascotState.vue'
import ProductImage from '@/components/ProductImage.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import { isPositiveApiId, normalizeApiId, type ApiId } from '@/api/ids'
import type {
  IdentityVerificationStatus,
  MembershipDashboard,
  MembershipLevel,
  PointsLedgerEntry,
} from '@/types'
import { couponStatusLabel, dateTime, membershipLevelLabel, money } from '@/utils/format'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'

type MembershipMutation = 'checkIn' | 'submitIdentity' | 'redeemPoints' | 'addCollection'

const notify = useNotify()
const { t } = useI18n()
const dashboardResource = useAsyncState<MembershipDashboard>({ timeoutMs: 20000 })
const levels: MembershipLevel[] = ['BASIC', 'SILVER', 'GOLD', 'DIAMOND']
const identityForm = reactive({ realName: '', idCardNo: '' })
const redeemForm = reactive({ points: 100 })
const collectionForm = reactive({
  productId: undefined as ApiId | undefined,
  targetPrice: undefined as number | undefined,
})
const recentLedger = ref<PointsLedgerEntry | null>(null)
const pending = reactive<Record<MembershipMutation, boolean>>({
  checkIn: false,
  submitIdentity: false,
  redeemPoints: false,
  addCollection: false,
})
const removingCollectionIds = reactive(new Set<string>())

const dashboard = dashboardResource.data
const profile = computed(() => dashboard.value?.profile)
const wallet = computed(() => dashboard.value?.wallet)
const coupons = computed(() => dashboard.value?.coupons ?? [])
const collections = computed(() => dashboard.value?.collections ?? [])
const browseHistory = computed(() => dashboard.value?.browseHistory ?? [])
const identityStatus = computed<IdentityVerificationStatus>(() => {
  const current = profile.value
  if (!current) return 'PENDING'
  return current.identityStatus ?? (current.verified ? 'VERIFIED' : 'PENDING')
})
const identityStatusLabel = computed(() => {
  if (identityStatus.value === 'VERIFIED') return t('membership.identityVerified')
  if (identityStatus.value === 'REJECTED') return t('membership.identityRejected')
  return t('membership.identityPending')
})
const identityTagType = computed<'success' | 'warning' | 'danger'>(() => {
  if (identityStatus.value === 'VERIFIED') return 'success'
  if (identityStatus.value === 'REJECTED') return 'danger'
  return 'warning'
})
const identitySummary = computed(() => {
  if (identityStatus.value === 'PENDING') {
    return profile.value?.identitySubmittedAt
      ? t('membership.identityPendingHint')
      : t('membership.identityNotSubmitted')
  }
  if (identityStatus.value === 'REJECTED') return t('membership.identityRejectedHint')
  const maskedValues = [profile.value?.maskedRealName, profile.value?.maskedIdCardNo].filter(Boolean)
  return maskedValues.length ? maskedValues.join(' / ') : t('membership.identityVerified')
})
const identityFormVisible = computed(
  () =>
    identityStatus.value === 'REJECTED' ||
    (identityStatus.value === 'PENDING' && !profile.value?.identitySubmittedAt),
)
const progress = computed(() => {
  const value = profile.value?.growthValue ?? 0
  const current = profile.value?.level ?? 'BASIC'
  const index = levels.indexOf(current)
  const next = levels[index + 1]
  if (!next) return 100
  const floor = levelMin(current)
  const ceiling = levelMin(next)
  return Math.max(0, Math.min(100, Math.round(((value - floor) / (ceiling - floor)) * 100)))
})
const nextLevel = computed(() => {
  const current = profile.value?.level ?? 'BASIC'
  return levels[levels.indexOf(current) + 1]
})
const canVerify = computed(() =>
  Boolean(identityForm.realName.trim() && identityForm.idCardNo.trim()),
)
const canRedeem = computed(() => {
  const points = redeemForm.points
  return points > 0 && points <= (wallet.value?.balance ?? 0)
})
const canAddCollection = computed(
  () =>
    isPositiveApiId(collectionForm.productId) &&
    (collectionForm.targetPrice === undefined || collectionForm.targetPrice >= 0),
)

function levelMin(level: MembershipLevel): number {
  return { BASIC: 0, SILVER: 1000, GOLD: 5000, DIAMOND: 20000 }[level]
}

function levelLabel(level: MembershipLevel | string): string {
  return membershipLevelLabel(level)
}

function couponTone(status: string): 'success' | 'info' | 'warning' | 'danger' {
  if (status === 'CLAIMED') return 'success'
  if (status === 'USED') return 'info'
  if (status === 'RETURNED') return 'warning'
  return 'danger'
}

async function loadDashboard() {
  await dashboardResource.load(() => membershipApi.membershipDashboard(), {
    preserveData: true,
  })
}

async function runMutation(key: MembershipMutation, mutation: () => Promise<void>) {
  if (pending[key]) return
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
    const intent = getIdempotencyIntent('membership:check-in', { action: 'daily-check-in' })
    const result = await membershipApi.checkIn(intent.key)
    intent.complete()
    notify.success(t('membership.checkedIn', { points: result.rewardPoints }), {
      key: 'membership:check-in:success',
    })
    await loadDashboard()
  })
}

async function submitIdentity() {
  if (!canVerify.value) return
  await runMutation('submitIdentity', async () => {
    dashboard.value = await membershipApi.submitIdentity({ ...identityForm })
    Object.assign(identityForm, { realName: '', idCardNo: '' })
    notify.success(t('membership.identitySubmitted'), { key: 'membership:identity:submitted' })
  })
}

async function redeemPoints() {
  if (!canRedeem.value) return
  await runMutation('redeemPoints', async () => {
    const payload = {
      points: redeemForm.points,
      referenceKey: 'wallet-redemption',
    }
    const intent = getIdempotencyIntent('membership:points:redeem', payload)
    recentLedger.value = await membershipApi.redeemPoints(payload, intent.key)
    intent.complete()
    notify.success(t('membership.redeemed'), { key: 'membership:points:redeemed' })
    await loadDashboard()
  })
}

async function addCollection() {
  const productId = collectionForm.productId
  if (!canAddCollection.value || productId === undefined) return
  await runMutation('addCollection', async () => {
    await membershipApi.addCollection({
      productId,
      targetPrice: collectionForm.targetPrice,
    })
    notify.success(t('membership.collectionAdded'), { key: 'membership:collection:added' })
    Object.assign(collectionForm, { productId: undefined, targetPrice: undefined })
    await loadDashboard()
  })
}

async function removeCollection(productId: ApiId) {
  const confirmed = await notify.confirm({
    title: t('common.confirm'),
    content: t('membership.removeConfirm'),
    type: 'warning',
  })
  const collectionKey = normalizeApiId(productId)
  if (!confirmed || removingCollectionIds.has(collectionKey)) return

  removingCollectionIds.add(collectionKey)
  try {
    await membershipApi.removeCollection(productId)
    notify.success(t('common.updated'), { key: `membership:collection:${productId}:removed` })
    await loadDashboard()
  } catch (caught) {
    notify.fromApiError(caught, 'membership.actionFailed')
  } finally {
    removingCollectionIds.delete(collectionKey)
  }
}

onMounted(() => {
  void loadDashboard()
})
</script>

<template>
  <div class="route-view membership-page">
    <PageHeader
      :title="$t('membership.center')"
      :eyebrow="levelLabel(profile?.level || 'BASIC')"
      :description="$t('membership.description')"
    >
      <template #actions>
        <el-button
          :icon="RefreshRight"
          :loading="dashboardResource.status.value === 'updating'"
          @click="loadDashboard"
        >
          {{ $t('common.refresh') }}
        </el-button>
        <el-button
          type="primary"
          :icon="Check"
          :loading="pending.checkIn"
          :disabled="pending.checkIn"
          @click="checkIn"
        >
          {{ $t('membership.checkIn') }}
        </el-button>
      </template>
    </PageHeader>

    <AsyncStateView
      :status="dashboardResource.status.value"
      :error="dashboardResource.error.value"
      @retry="loadDashboard"
    >
      <section class="member-hero" :aria-label="$t('membership.center')">
        <div class="member-hero__mascot">
          <MascotState pose="celebrate" size="sm" eager :alt="$t('membership.heroMascotAlt')" />
        </div>

        <div class="member-progress">
          <div class="member-progress__title">
            <span>{{ $t('membership.currentLevel') }}</span>
            <strong>{{ levelLabel(profile?.level || 'BASIC') }}</strong>
          </div>
          <el-progress
            :percentage="progress"
            :stroke-width="8"
            :show-text="false"
            :aria-label="$t('membership.growthValue')"
          />
          <p>
            {{
              nextLevel
                ? $t('membership.nextLevelHint', {
                    level: levelLabel(nextLevel),
                    value: levelMin(nextLevel),
                  })
                : $t('membership.highestLevel')
            }}
          </p>
        </div>

        <dl class="membership-metrics">
          <div>
            <dt>{{ $t('membership.pointsBalance') }}</dt>
            <dd>
              <strong>{{ wallet?.balance ?? 0 }}</strong>
              <small>{{
                $t('membership.moneyEquivalent', { amount: wallet?.moneyEquivalent ?? 0 })
              }}</small>
            </dd>
          </div>
          <div>
            <dt>{{ $t('membership.couponAccount') }}</dt>
            <dd>
              <strong>{{ coupons.length }}</strong>
              <small>{{ $t('membership.availableBenefits') }}</small>
            </dd>
          </div>
          <div>
            <dt>{{ $t('membership.priceWatch') }}</dt>
            <dd>
              <strong>{{ collections.length }}</strong>
              <small>{{ $t('membership.activeWatches') }}</small>
            </dd>
          </div>
        </dl>

        <div v-if="profile?.benefits?.length" class="benefit-list">
          <span v-for="benefit in profile.benefits" :key="benefit">
            <el-icon aria-hidden="true"><Star /></el-icon>
            {{ benefit }}
          </span>
        </div>
      </section>

      <section class="membership-section" data-membership-section="identity">
        <header class="section-heading">
          <div>
            <h2>{{ $t('membership.identityStatus') }}</h2>
            <p>{{ identitySummary }}</p>
          </div>
          <el-tag :type="identityTagType" disable-transitions>
            {{ identityStatusLabel }}
          </el-tag>
        </header>

        <p v-if="identityStatus === 'PENDING' && profile?.identitySubmittedAt" class="identity-note">
          {{ $t('membership.identityPendingHint') }}
        </p>
        <form v-if="identityFormVisible" class="identity-form" @submit.prevent="submitIdentity">
          <el-input
            v-model="identityForm.realName"
            :aria-label="$t('membership.realName')"
            :placeholder="$t('membership.realName')"
            autocomplete="name"
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
            native-type="button"
            :loading="pending.submitIdentity"
            :disabled="pending.submitIdentity || !canVerify"
            @click="submitIdentity"
          >
            {{ $t('membership.submitIdentity') }}
          </el-button>
        </form>
      </section>

      <section class="membership-section" data-membership-section="points">
        <header class="section-heading">
          <div>
            <h2>{{ $t('membership.pointsAccount') }}</h2>
            <p>{{ $t('membership.pointsHint') }}</p>
          </div>
          <span class="points-earned">
            {{ $t('membership.totalEarned') }} {{ wallet?.totalEarned ?? 0 }} /
            {{ $t('membership.totalSpent', { spent: wallet?.totalSpent ?? 0 }) }}
          </span>
        </header>

        <form class="redeem-form" @submit.prevent="redeemPoints">
          <el-input-number
            v-model="redeemForm.points"
            :aria-label="$t('membership.pointsToRedeem')"
            :min="1"
            :max="Math.max(1, wallet?.balance ?? 1)"
            :step="100"
            controls-position="right"
          />
          <el-button
            type="primary"
            native-type="button"
            :icon="Tickets"
            :loading="pending.redeemPoints"
            :disabled="pending.redeemPoints || !canRedeem"
            @click="redeemPoints"
          >
            {{ $t('membership.redeem') }}
          </el-button>
        </form>

        <dl v-if="recentLedger" class="recent-ledger" aria-live="polite">
          <div>
            <dt>{{ $t('membership.latestActivity') }}</dt>
            <dd>{{ recentLedger.points }}</dd>
          </div>
          <div>
            <dt>{{ $t('membership.moneyEquivalentLabel') }}</dt>
            <dd>{{ money(recentLedger.moneyEquivalent) }}</dd>
          </div>
          <div>
            <dt>{{ $t('membership.activityTime') }}</dt>
            <dd>{{ dateTime(recentLedger.createdAt) }}</dd>
          </div>
        </dl>
      </section>

      <section class="membership-section" data-membership-section="price-watch">
        <header class="section-heading">
          <div>
            <h2>{{ $t('membership.priceWatch') }}</h2>
            <p>{{ $t('membership.priceWatchHint') }}</p>
          </div>
        </header>

        <form class="collection-form" @submit.prevent="addCollection">
          <el-input
            v-model="collectionForm.productId"
            :aria-label="$t('common.product')"
            :placeholder="$t('membership.productIdPlaceholder')"
          />
          <el-input-number
            v-model="collectionForm.targetPrice"
            :aria-label="$t('membership.targetPrice')"
            :placeholder="$t('membership.targetPrice')"
            :min="0"
            :step="10"
            :precision="2"
            controls-position="right"
          />
          <el-button
            type="primary"
            native-type="button"
            :loading="pending.addCollection"
            :disabled="pending.addCollection || !canAddCollection"
            @click="addCollection"
          >
            {{ $t('membership.addPriceWatch') }}
          </el-button>
        </form>

        <DataTableShell :aria-label="$t('membership.priceWatch')" :empty="collections.length === 0">
          <template #empty>
            <div class="collection-empty">
              <MascotState
                pose="shoppingBag"
                size="sm"
                :alt="$t('membership.emptyWatchMascotAlt')"
              />
              <p>{{ $t('membership.emptyWatch') }}</p>
            </div>
          </template>

          <div class="collection-list">
            <article v-for="item in collections" :key="item.productId" class="collection-row">
              <ProductImage :src="item.productImage" :alt="item.productName" />
              <div class="collection-row__main">
                <strong>{{ item.productName }}</strong>
                <span>{{ $t('membership.lastPrice') }} {{ money(item.lastPrice) }}</span>
              </div>
              <div class="collection-row__target">
                <span>{{ $t('membership.targetPrice') }}</span>
                <strong>{{
                  item.targetPrice === undefined ? '-' : money(item.targetPrice)
                }}</strong>
              </div>
              <el-tag :type="item.priceDropNotified ? 'success' : 'info'" disable-transitions>
                {{
                  item.priceDropNotified
                    ? $t('membership.notifiedYes')
                    : $t('membership.notifiedNo')
                }}
              </el-tag>
              <el-button
                type="danger"
                plain
                :loading="removingCollectionIds.has(normalizeApiId(item.productId))"
                :disabled="removingCollectionIds.has(normalizeApiId(item.productId))"
                @click="removeCollection(item.productId)"
              >
                {{ $t('common.delete') }}
              </el-button>
            </article>
          </div>
        </DataTableShell>
      </section>

      <section class="membership-section" data-membership-section="coupons">
        <header class="section-heading">
          <div>
            <h2>{{ $t('membership.couponAccount') }}</h2>
            <p>{{ $t('membership.couponHint') }}</p>
          </div>
        </header>

        <DataTableShell :aria-label="$t('membership.couponAccount')" :empty="coupons.length === 0">
          <template #empty>{{ $t('membership.noCoupons') }}</template>
          <div class="coupon-list">
            <article v-for="coupon in coupons" :key="coupon.id" class="coupon-row">
              <div>
                <span>{{ $t('membership.couponCode') }}</span>
                <strong>{{ coupon.couponCode }}</strong>
              </div>
              <el-tag :type="couponTone(coupon.status)" disable-transitions>
                {{ couponStatusLabel(coupon.status) }}
              </el-tag>
              <time :datetime="coupon.claimedAt">{{ dateTime(coupon.claimedAt) }}</time>
            </article>
          </div>
        </DataTableShell>
      </section>

      <section class="membership-section" data-membership-section="history">
        <header class="section-heading">
          <div>
            <h2>{{ $t('membership.browseHistory') }}</h2>
            <p>{{ $t('membership.historyHint') }}</p>
          </div>
        </header>

        <DataTableShell
          :aria-label="$t('membership.browseHistory')"
          :empty="browseHistory.length === 0"
        >
          <template #empty>{{ $t('membership.noHistory') }}</template>
          <div class="history-list">
            <article
              v-for="item in browseHistory"
              :key="`${item.productId}:${item.viewedAt}`"
              class="history-row"
            >
              <ProductImage :src="item.productImage" :alt="item.productName" />
              <strong>{{ item.productName }}</strong>
              <div>
                <span>{{ $t('membership.viewedAt') }}</span>
                <time :datetime="item.viewedAt">{{ dateTime(item.viewedAt) }}</time>
              </div>
              <div>
                <span>{{ $t('membership.expiresAt') }}</span>
                <time :datetime="item.expiresAt">{{ dateTime(item.expiresAt) }}</time>
              </div>
            </article>
          </div>
        </DataTableShell>
      </section>
    </AsyncStateView>
  </div>
</template>

<style scoped>
.membership-page,
.membership-page :deep(.async-state-view__content),
.member-hero,
.member-progress,
.membership-section,
.section-heading > div,
.collection-list,
.coupon-list,
.history-list {
  display: grid;
}

.membership-page,
.membership-page :deep(.async-state-view__content) {
  gap: var(--space-5);
}

.member-hero {
  grid-template-columns: 128px minmax(220px, 0.8fr) minmax(0, 1.4fr);
  gap: var(--space-5);
  align-items: center;
  padding: var(--space-5) var(--space-6);
  border-block: 1px solid var(--color-line);
  background: var(--color-brand-soft);
}

.member-hero__mascot {
  display: grid;
  align-self: stretch;
  place-items: end center;
  min-width: 0;
}

.member-hero__mascot :deep(.mascot-state) {
  filter: drop-shadow(0 8px 12px color-mix(in srgb, var(--color-text) 14%, transparent));
}

.member-progress {
  gap: var(--space-3);
}

.member-progress__title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-3);
}

.member-progress__title span,
.member-progress p,
.membership-metrics dt,
.membership-metrics small,
.section-heading p,
.points-earned,
.collection-row span,
.coupon-row span,
.coupon-row time,
.history-row span,
.history-row time {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.member-progress__title strong {
  color: var(--color-brand-strong);
  font-size: var(--text-2xl);
  font-weight: 800;
}

.member-hero .member-progress__title span,
.member-hero .member-progress p,
.member-hero .membership-metrics dt,
.member-hero .membership-metrics small {
  color: var(--color-text);
}

.member-progress p,
.section-heading h2,
.section-heading p,
.collection-empty p {
  margin: 0;
}

.membership-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0;
}

.membership-metrics div {
  min-width: 0;
  padding-inline: var(--space-4);
  border-left: 1px solid var(--color-line-strong);
}

.membership-metrics dd {
  display: grid;
  gap: var(--space-1);
  margin: var(--space-1) 0;
  overflow-wrap: anywhere;
}

.membership-metrics dd strong {
  font-size: var(--text-2xl);
  font-weight: 800;
}

.benefit-list {
  display: flex;
  grid-column: 2 / -1;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.benefit-list span {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  padding: var(--space-1) var(--space-2);
  border: 1px solid var(--color-line-strong);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  color: var(--color-text);
  font-size: var(--text-xs);
  font-weight: 700;
}

.membership-section {
  gap: var(--space-4);
  padding-bottom: var(--space-5);
  border-bottom: 1px solid var(--color-line);
}

.membership-section:last-child {
  border-bottom: 0;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.section-heading > div {
  gap: var(--space-1);
}

.section-heading h2 {
  font-size: var(--text-lg);
}

.identity-form,
.redeem-form,
.collection-form {
  display: grid;
  align-items: start;
  gap: var(--space-3);
}

.identity-note {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.identity-form {
  grid-template-columns: minmax(160px, 0.8fr) minmax(220px, 1.2fr) auto;
}

.redeem-form {
  grid-template-columns: minmax(180px, 280px) auto;
  justify-content: start;
}

.collection-form {
  grid-template-columns: minmax(180px, 240px) minmax(180px, 240px) max-content;
}

.identity-form > .el-button,
.redeem-form > .el-button,
.collection-form > .el-button {
  min-height: 40px;
  min-width: 160px;
  justify-self: start;
}

.identity-form :deep(.el-input-number),
.redeem-form :deep(.el-input-number),
.collection-form :deep(.el-input-number) {
  width: 100%;
}

.recent-ledger {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 0;
  padding: var(--space-3) 0;
  border-block: 1px solid var(--color-line);
}

.recent-ledger div {
  padding-inline: var(--space-4);
  border-right: 1px solid var(--color-line);
}

.recent-ledger div:first-child {
  padding-left: 0;
}

.recent-ledger div:last-child {
  border-right: 0;
}

.recent-ledger dt {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.recent-ledger dd {
  margin: var(--space-1) 0 0;
  font-weight: 750;
}

.collection-empty {
  display: grid;
  justify-items: center;
  gap: var(--space-2);
  padding: var(--space-4);
}

.collection-row,
.coupon-row,
.history-row {
  display: grid;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
  border-bottom: 1px solid var(--color-line);
}

.collection-row:last-child,
.coupon-row:last-child,
.history-row:last-child {
  border-bottom: 0;
}

.collection-row {
  grid-template-columns: 64px minmax(180px, 1fr) minmax(120px, 0.5fr) auto auto;
}

.collection-row :deep(.product-image),
.history-row :deep(.product-image) {
  width: 64px;
  height: 64px;
  aspect-ratio: 1;
}

.collection-row__main,
.collection-row__target,
.coupon-row > div,
.history-row > div {
  display: grid;
  gap: var(--space-1);
}

.collection-row__target {
  justify-items: end;
}

.coupon-row {
  grid-template-columns: minmax(0, 1fr) auto minmax(160px, auto);
}

.history-row {
  grid-template-columns: 64px minmax(180px, 1fr) minmax(160px, auto) minmax(160px, auto);
}

@media (max-width: 920px) {
  .member-hero {
    grid-template-columns: 112px minmax(0, 1fr);
  }

  .membership-metrics,
  .benefit-list {
    grid-column: 1 / -1;
  }

  .membership-metrics div:first-child {
    border-left: 0;
    padding-left: 0;
  }

  .identity-form,
  .collection-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .identity-form > .el-button,
  .collection-form > .el-button {
    grid-column: 1 / -1;
    justify-self: start;
  }

  .collection-row,
  .history-row {
    grid-template-columns: 64px minmax(0, 1fr) auto;
  }

  .collection-row__target,
  .history-row > div {
    justify-items: start;
  }

  .collection-row > .el-tag,
  .collection-row > .el-button,
  .history-row > div {
    grid-column: 2 / -1;
  }
}

@media (max-width: 640px) {
  .member-hero {
    grid-template-columns: 88px minmax(0, 1fr);
    gap: var(--space-3);
    padding: var(--space-4);
  }

  .member-hero__mascot :deep(.mascot-state) {
    --mascot-size: 88px;
  }

  .membership-metrics,
  .identity-form,
  .redeem-form,
  .collection-form,
  .recent-ledger {
    grid-template-columns: 1fr;
  }

  .membership-metrics div {
    padding: var(--space-3) 0;
    border-top: 1px solid var(--color-line);
    border-left: 0;
  }

  .benefit-list {
    grid-column: 1 / -1;
  }

  .section-heading {
    display: grid;
  }

  .identity-form > .el-button,
  .redeem-form > .el-button,
  .collection-form > .el-button {
    grid-column: auto;
    width: 100%;
    min-height: 44px;
  }

  .recent-ledger div,
  .recent-ledger div:first-child {
    padding: var(--space-2) 0;
    border-right: 0;
    border-bottom: 1px solid var(--color-line);
  }

  .recent-ledger div:last-child {
    border-bottom: 0;
  }

  .collection-row,
  .history-row {
    grid-template-columns: 56px minmax(0, 1fr);
    gap: var(--space-3);
  }

  .coupon-row {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: var(--space-3);
  }

  .collection-row :deep(.product-image),
  .history-row :deep(.product-image) {
    width: 56px;
    height: 56px;
  }

  .collection-row__target,
  .collection-row > .el-tag,
  .collection-row > .el-button,
  .coupon-row > time,
  .history-row > div {
    grid-column: 1 / -1;
    justify-items: start;
  }

  .collection-row > .el-button {
    width: 100%;
    min-height: 44px;
  }

  .coupon-row > .el-tag {
    justify-self: end;
  }
}
</style>

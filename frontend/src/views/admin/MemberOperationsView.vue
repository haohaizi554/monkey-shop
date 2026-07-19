<script setup lang="ts">
import { Medal, RefreshRight, Search, SetUp, TrendCharts } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import {
  adminChangeLevel,
  adminEarnPoints,
  adminMembershipDashboard,
  scanPriceDrops,
} from '@/api/membership'
import AdminCommerceNav from '@/components/admin/AdminCommerceNav.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type { MembershipDashboard, MembershipLevel, PriceDropScanResult } from '@/types'
import { membershipLevelLabel } from '@/utils/format'
import { getIdempotencyIntent } from '@/utils/idempotencyIntent'

defineOptions({ name: 'MemberOperationsView' })

const levels: MembershipLevel[] = ['BASIC', 'SILVER', 'GOLD', 'DIAMOND']
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const notify = useNotify()
const dashboardState = useAsyncState<MembershipDashboard>({ preserveData: true })
const targetUserId = ref<number>()
const pointsAmount = ref<number>()
const adjustmentReason = ref('')
const selectedLevel = ref<MembershipLevel>('BASIC')
const levelReason = ref('')
const totpCode = ref('')
const pointsPending = ref(false)
const levelPending = ref(false)
const scanPending = ref(false)
const scanResult = ref<PriceDropScanResult>()
const dashboard = computed(() => dashboardState.data.value)
const activeMemberId = computed(() => dashboard.value?.profile.userId)
const loadedTargetReady = computed(
  () =>
    Boolean(activeMemberId.value) &&
    activeMemberId.value === Number(targetUserId.value) &&
    !dashboardState.isLoading.value,
)
const canAdjustPoints = computed(
  () =>
    loadedTargetReady.value &&
    Number.isInteger(pointsAmount.value) &&
    Number(pointsAmount.value) > 0 &&
    adjustmentReason.value.trim().length > 0 &&
    !pointsPending.value,
)
const canChangeLevel = computed(
  () =>
    loadedTargetReady.value &&
    selectedLevel.value !== dashboard.value?.profile.level &&
    levelReason.value.trim().length > 0 &&
    /^\d{6}$/.test(totpCode.value.trim()) &&
    !levelPending.value,
)

async function loadMember(userId: number) {
  const loaded = await dashboardState.load(() => adminMembershipDashboard(userId), {
    preserveData: true,
  })
  if (loaded) {
    selectedLevel.value = loaded.profile.level
    pointsAmount.value = undefined
    adjustmentReason.value = ''
    levelReason.value = ''
    totpCode.value = ''
  }
}

async function submitLookup() {
  const userId = Number(targetUserId.value)
  if (!Number.isInteger(userId) || userId <= 0) {
    return
  }
  const queryUserId = String(userId)
  if (route.query.userId !== queryUserId) {
    await router.replace({ query: { ...route.query, userId: queryUserId } })
    return
  }
  await loadMember(userId)
}

async function applyPoints() {
  const userId = activeMemberId.value
  if (!userId || !canAdjustPoints.value) {
    return
  }
  const payload = {
    amount: String(pointsAmount.value),
    referenceKey: adjustmentReason.value.trim(),
  }
  const accepted = await notify.confirm({
    content: t('adminCommerce.pointsConfirm', {
      points: payload.amount,
      userId,
      reason: payload.referenceKey,
    }),
    confirmText: t('adminCommerce.applyPoints'),
    type: 'warning',
  })
  if (!accepted) {
    return
  }

  pointsPending.value = true
  try {
    const intent = getIdempotencyIntent(`admin:member-points:${userId}`, payload)
    await adminEarnPoints(userId, payload, intent.key)
    intent.complete()
    await loadMember(userId)
    notify.success(t('adminCommerce.pointsAdjusted'), {
      key: `member:points:${userId}`,
    })
  } catch (caught) {
    notify.fromApiError(caught, 'adminCommerce.unableToAdjustPoints')
  } finally {
    pointsPending.value = false
  }
}

async function changeLevel() {
  const userId = activeMemberId.value
  if (!userId || !canChangeLevel.value) {
    return
  }
  const payload = {
    level: selectedLevel.value,
    reason: levelReason.value.trim(),
    totpCode: totpCode.value.trim(),
  }
  const accepted = await notify.confirm({
    content: t('adminCommerce.levelConfirm', {
      level: membershipLevelLabel(payload.level),
      userId,
      reason: payload.reason,
    }),
    confirmText: t('adminCommerce.changeLevel'),
    type: 'warning',
  })
  if (!accepted) {
    return
  }

  levelPending.value = true
  try {
    const updated = await adminChangeLevel(userId, payload)
    dashboardState.data.value = updated
    dashboardState.status.value = 'success'
    selectedLevel.value = updated.profile.level
    levelReason.value = ''
    totpCode.value = ''
    notify.success(t('adminCommerce.levelChanged'), {
      key: `member:level:${userId}`,
    })
  } catch (caught) {
    notify.fromApiError(caught, 'adminCommerce.unableToChangeLevel')
  } finally {
    levelPending.value = false
  }
}

async function runPriceDropScan() {
  if (scanPending.value) {
    return
  }
  scanPending.value = true
  try {
    scanResult.value = await scanPriceDrops()
    notify.success(t('adminCommerce.scanComplete'), {
      key: 'membership:price-drop-scan',
    })
  } catch (caught) {
    notify.fromApiError(caught, 'adminCommerce.unableToScanPriceDrops')
  } finally {
    scanPending.value = false
  }
}

watch(
  () => route.query.userId,
  (value) => {
    const raw = Array.isArray(value) ? value[0] : value
    const userId = Number(raw)
    if (Number.isInteger(userId) && userId > 0) {
      targetUserId.value = userId
      void loadMember(userId)
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="route-view commerce-page">
    <PageHeader
      :eyebrow="t('adminCommerce.workspace')"
      :title="t('adminCommerce.membersTitle')"
      :description="t('adminCommerce.membersDescription')"
    />

    <AdminCommerceNav />

    <section class="commerce-section" aria-labelledby="member-lookup-title">
      <div class="commerce-section__heading">
        <div>
          <h2 id="member-lookup-title">{{ t('adminCommerce.memberLookupTitle') }}</h2>
          <p>{{ t('adminCommerce.memberLookupDescription') }}</p>
        </div>
      </div>

      <div class="commerce-form-grid">
        <div class="commerce-field">
          <span>{{ t('adminCommerce.memberId') }}</span>
          <el-input-number
            id="member-user-id"
            v-model="targetUserId"
            :min="1"
            :step="1"
            controls-position="right"
            :aria-label="t('adminCommerce.memberId')"
            @keyup.enter="submitLookup"
          />
        </div>
        <div class="commerce-actions">
          <el-button
            type="primary"
            :icon="Search"
            :loading="dashboardState.isLoading.value"
            :disabled="!targetUserId"
            @click="submitLookup"
          >
            {{ t('adminCommerce.loadMember') }}
          </el-button>
          <el-button
            v-if="activeMemberId"
            :icon="RefreshRight"
            :loading="dashboardState.isLoading.value"
            @click="loadMember(activeMemberId)"
          >
            {{ t('common.refresh') }}
          </el-button>
        </div>
      </div>

      <AsyncStateView
        :status="dashboardState.status.value"
        mode="detail"
        :error="dashboardState.error.value"
        preserve-content-on-error
        @retry="activeMemberId && loadMember(activeMemberId)"
      >
        <template #idle>
          <p class="commerce-inline-state">{{ t('adminCommerce.noMemberSelected') }}</p>
        </template>

        <dl v-if="dashboard" class="commerce-kv">
          <div>
            <dt>{{ t('adminCommerce.memberId') }}</dt>
            <dd>{{ dashboard.profile.userId }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.memberLevel') }}</dt>
            <dd>{{ membershipLevelLabel(dashboard.profile.level) }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.pointsBalance') }}</dt>
            <dd>{{ dashboard.wallet.balance }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.growthValue') }}</dt>
            <dd>{{ dashboard.profile.growthValue }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.totalEarned') }}</dt>
            <dd>{{ dashboard.wallet.totalEarned }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.totalSpent') }}</dt>
            <dd>{{ dashboard.wallet.totalSpent }}</dd>
          </div>
          <div>
            <dt>{{ t('adminCommerce.verified') }}</dt>
            <dd>{{ t(dashboard.profile.verified ? 'common.yes' : 'common.no') }}</dd>
          </div>
        </dl>
      </AsyncStateView>
    </section>

    <section class="commerce-section" aria-labelledby="member-points-title">
      <div class="commerce-section__heading">
        <div>
          <h2 id="member-points-title">{{ t('adminCommerce.manualPointsTitle') }}</h2>
          <p>{{ t('adminCommerce.manualPointsDescription') }}</p>
        </div>
        <Medal aria-hidden="true" />
      </div>

      <div class="commerce-form-grid">
        <div class="commerce-field">
          <span>{{ t('adminCommerce.points') }}</span>
          <el-input-number
            id="member-points"
            v-model="pointsAmount"
            :min="1"
            :step="1"
            :precision="0"
            controls-position="right"
            :aria-label="t('adminCommerce.points')"
          />
        </div>
        <div class="commerce-field">
          <span>{{ t('adminCommerce.adjustmentReason') }}</span>
          <el-input
            id="member-points-reason"
            v-model="adjustmentReason"
            :placeholder="t('adminCommerce.adjustmentReasonPlaceholder')"
            :aria-label="t('adminCommerce.adjustmentReason')"
          />
        </div>
        <div class="commerce-actions commerce-actions--end commerce-field--wide">
          <el-button
            type="primary"
            :icon="SetUp"
            :loading="pointsPending"
            :disabled="!canAdjustPoints"
            @click="applyPoints"
          >
            {{ t('adminCommerce.applyPoints') }}
          </el-button>
        </div>
      </div>
    </section>

    <section class="commerce-section" aria-labelledby="member-level-title">
      <div class="commerce-section__heading">
        <div>
          <h2 id="member-level-title">{{ t('adminCommerce.levelManagementTitle') }}</h2>
          <p>{{ t('adminCommerce.levelManagementDescription') }}</p>
        </div>
      </div>

      <div class="commerce-form-grid" data-columns="3">
        <div class="commerce-field">
          <span>{{ t('adminCommerce.memberLevel') }}</span>
          <el-select
            id="member-level"
            v-model="selectedLevel"
            :aria-label="t('adminCommerce.memberLevel')"
          >
            <el-option
              v-for="level in levels"
              :key="level"
              :label="membershipLevelLabel(level)"
              :value="level"
            />
          </el-select>
        </div>
        <div class="commerce-field">
          <span>{{ t('adminCommerce.levelChangeReason') }}</span>
          <el-input
            id="member-level-reason"
            v-model="levelReason"
            :placeholder="t('adminCommerce.levelChangeReasonPlaceholder')"
            :aria-label="t('adminCommerce.levelChangeReason')"
          />
        </div>
        <div class="commerce-field">
          <span>{{ t('adminCommerce.totpCode') }}</span>
          <el-input
            id="member-level-totp"
            v-model="totpCode"
            inputmode="numeric"
            maxlength="6"
            autocomplete="one-time-code"
            :placeholder="t('adminCommerce.totpPlaceholder')"
            :aria-label="t('adminCommerce.totpCode')"
          />
        </div>
        <div class="commerce-actions commerce-actions--end commerce-field--wide">
          <el-button
            type="primary"
            :icon="Medal"
            :loading="levelPending"
            :disabled="!canChangeLevel"
            @click="changeLevel"
          >
            {{ t('adminCommerce.changeLevel') }}
          </el-button>
        </div>
      </div>
    </section>

    <section class="commerce-section" aria-labelledby="member-automation-title">
      <div class="commerce-section__heading">
        <div>
          <h2 id="member-automation-title">{{ t('adminCommerce.automationTitle') }}</h2>
          <p>{{ t('adminCommerce.automationDescription') }}</p>
        </div>
        <el-button
          type="primary"
          plain
          :icon="TrendCharts"
          :loading="scanPending"
          :disabled="scanPending"
          @click="runPriceDropScan"
        >
          {{ t('adminCommerce.runPriceDropScan') }}
        </el-button>
      </div>

      <dl v-if="scanResult" class="commerce-kv" aria-live="polite">
        <div>
          <dt>{{ t('adminCommerce.scannedCollections') }}</dt>
          <dd>{{ scanResult.scanned }}</dd>
        </div>
        <div>
          <dt>{{ t('adminCommerce.remindersCreated') }}</dt>
          <dd>{{ scanResult.reminders }}</dd>
        </div>
      </dl>
    </section>
  </div>
</template>

<script setup lang="ts">
import {
  ArrowLeft,
  CircleCheck,
  Clock,
  InfoFilled,
  Plus,
  Refresh,
  WarningFilled,
} from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, onMounted, reactive, ref, watch, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import * as tenantApi from '@/api/tenant'
import MetricStrip, { type MetricItem } from '@/components/admin/MetricStrip.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type {
  Tenant,
  TenantBill,
  TenantConfig,
  TenantConfigType,
  TenantDashboard,
  TenantExportJob,
  TenantPlan,
} from '@/types'
import { money } from '@/utils/format'

defineOptions({ name: 'TenantAdminView' })

interface TenantConfigDraft {
  provider: string
  enabled: boolean
  settingsText: string
  dirty: boolean
}

type TenantStatus = Tenant['status']
type BillStatus = TenantBill['status']
type ExportType = 'FULL' | 'ORDERS' | 'USERS'
type ExportStatus = TenantExportJob['status']

const { locale, t } = useI18n()
const route = useRoute()
const router = useRouter()
const notify = useNotify()
const listState = useAsyncState<TenantDashboard>()
const configState = useAsyncState<TenantConfig[]>({ preserveData: false })
const billState = useAsyncState<TenantBill[]>({ preserveData: false })
const exportState = useAsyncState<TenantExportJob[]>({ preserveData: false })
const selectedTenantId = ref<number>()
const activeTab = ref('config')
const createDialogOpen = ref(false)
const createFormRef = ref<FormInstance>()
const mobileDetailVisible = ref(false)
const pendingKeys = ref(new Set<string>())
const configError = ref('')
const billError = ref('')
const tenantDashboardLastSuccessAt = ref<Date>()
const activeConfigTenantId = ref<number>()
const activeConfigType = ref<TenantConfigType>('PAYMENT')
const configDrafts = new Map<string, TenantConfigDraft>()
const selectedConfigTypeByTenant = new Map<number, TenantConfigType>()
const configTypes: TenantConfigType[] = ['PAYMENT', 'LOGISTICS', 'MARKETING', 'ROLLOUT']
let detailRequestVersion = 0
let syncingConfigForm = false

const createForm = reactive({
  code: '',
  name: '',
  plan: 'STARTER' as TenantPlan,
  contactName: '',
  contactPhone: '',
  months: 12,
})
const configForm = reactive({
  configType: 'PAYMENT' as TenantConfigType,
  provider: 'wechat',
  enabled: true,
  settingsText: '{\n  "merchantId": "demo"\n}',
})
const billForm = reactive({ billingMonth: new Date().toISOString().slice(0, 7) })
const exportForm = reactive({ exportType: 'FULL' as ExportType })

const createRules = computed<FormRules>(() => ({
  code: [
    {
      required: true,
      whitespace: true,
      message: t('tenant.codeRequired'),
      trigger: ['blur', 'change'],
    },
  ],
  name: [
    {
      required: true,
      whitespace: true,
      message: t('tenant.nameRequired'),
      trigger: ['blur', 'change'],
    },
  ],
  plan: [{ required: true, message: t('tenant.planRequired'), trigger: 'change' }],
  months: [
    {
      type: 'number',
      min: 1,
      max: 36,
      message: t('tenant.monthsRange'),
      trigger: 'change',
    },
  ],
}))
const dashboard = computed(() => listState.data.value)
const tenantList = computed(() => dashboard.value?.tenants ?? [])
const selectedTenant = computed(() =>
  tenantList.value.find((tenant) => tenant.id === selectedTenantId.value),
)
const configs = computed(() => configState.data.value ?? [])
const bills = computed(() => billState.data.value ?? [])
const exports = computed(() => exportState.data.value ?? [])
const metrics = computed<MetricItem[]>(() => [
  {
    key: 'active',
    label: t('tenant.activeTenants'),
    value: dashboard.value?.activeTenants ?? 0,
    tone: 'success',
  },
  {
    key: 'expired',
    label: t('tenant.expiredTenants'),
    value: dashboard.value?.expiredTenants ?? 0,
    tone: 'warning',
  },
  {
    key: 'orders',
    label: t('tenant.currentMonthOrders'),
    value: dashboard.value?.currentMonthOrders ?? 0,
  },
  {
    key: 'revenue',
    label: t('tenant.currentMonthRevenue'),
    value: money(dashboard.value?.currentMonthRevenue),
    tone: 'success',
  },
])

const planLabels = computed<Record<TenantPlan, string>>(() => ({
  STARTER: t('tenant.planStarter'),
  GROWTH: t('tenant.planGrowth'),
  ENTERPRISE: t('tenant.planEnterprise'),
}))
const tenantStatusLabels = computed<Record<TenantStatus, string>>(() => ({
  TRIAL: t('tenant.statusTrial'),
  ACTIVE: t('tenant.statusActive'),
  EXPIRED: t('tenant.statusExpired'),
  DOWNGRADED: t('tenant.statusDowngraded'),
  SUSPENDED: t('tenant.statusSuspended'),
}))
const configTypeLabels = computed<Record<TenantConfigType, string>>(() => ({
  PAYMENT: t('tenant.configTypePayment'),
  LOGISTICS: t('tenant.configTypeLogistics'),
  MARKETING: t('tenant.configTypeMarketing'),
  ROLLOUT: t('tenant.configTypeRollout'),
}))
const billStatusLabels = computed<Record<BillStatus, string>>(() => ({
  GENERATED: t('tenant.billStatusGenerated'),
  RECONCILED: t('tenant.billStatusReconciled'),
  SUSPENDED: t('tenant.billStatusSuspended'),
}))
const exportStatusLabels = computed<Record<ExportStatus, string>>(() => ({
  REQUESTED: t('tenant.exportStatusRequested'),
  COMPLETED: t('tenant.exportStatusCompleted'),
  FAILED: t('tenant.exportStatusFailed'),
}))
const exportTypeLabels = computed<Record<ExportType, string>>(() => ({
  FULL: t('tenant.exportFull'),
  ORDERS: t('tenant.exportOrders'),
  USERS: t('tenant.exportUsers'),
}))

function firstQueryValue(value: unknown): string {
  return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '')
}

function queryTenantId(): number | undefined {
  const value = Number.parseInt(firstQueryValue(route.query.tenant), 10)
  return Number.isInteger(value) && value > 0 ? value : undefined
}

function formatSuccessfulRefresh(value?: Date): string {
  if (!value) return '-'
  return new Intl.DateTimeFormat(locale.value, {
    dateStyle: 'short',
    timeStyle: 'medium',
  }).format(value)
}

function configDraftKey(tenantId: number, configType: TenantConfigType): string {
  return `${tenantId}:${configType}`
}

function configDraftFromServer(
  configType: TenantConfigType,
  rows: TenantConfig[],
): TenantConfigDraft {
  const saved = rows.find((row) => row.configType === configType)
  if (!saved) {
    return {
      provider: configType === 'PAYMENT' ? 'wechat' : '',
      enabled: true,
      settingsText: configType === 'PAYMENT' ? '{\n  "merchantId": "demo"\n}' : '{}',
      dirty: false,
    }
  }
  return {
    provider: saved.provider,
    enabled: saved.enabled,
    settingsText: JSON.stringify(saved.settings ?? {}, null, 2),
    dirty: false,
  }
}

function renderConfigDraft(
  tenantId: number,
  configType: TenantConfigType,
  draft: TenantConfigDraft,
) {
  syncingConfigForm = true
  activeConfigTenantId.value = tenantId
  activeConfigType.value = configType
  configForm.configType = configType
  configForm.provider = draft.provider
  configForm.enabled = draft.enabled
  configForm.settingsText = draft.settingsText
  configError.value = ''
  syncingConfigForm = false
}

function persistActiveConfigDraft() {
  const tenantId = activeConfigTenantId.value
  if (!tenantId) return
  configDrafts.set(configDraftKey(tenantId, activeConfigType.value), {
    provider: configForm.provider,
    enabled: configForm.enabled,
    settingsText: configForm.settingsText,
    dirty: true,
  })
}

function prepareTenantConfigDraft(tenantId: number) {
  const configType = selectedConfigTypeByTenant.get(tenantId) ?? 'PAYMENT'
  const draft = configDrafts.get(configDraftKey(tenantId, configType))
  renderConfigDraft(tenantId, configType, draft ?? configDraftFromServer(configType, []))
}

function initializeTenantConfigDrafts(tenantId: number, rows: TenantConfig[]) {
  for (const configType of configTypes) {
    const key = configDraftKey(tenantId, configType)
    const current = configDrafts.get(key)
    if (!current?.dirty) {
      configDrafts.set(key, configDraftFromServer(configType, rows))
    }
  }
  const configType = selectedConfigTypeByTenant.get(tenantId) ?? 'PAYMENT'
  selectedConfigTypeByTenant.set(tenantId, configType)
  const draft = configDrafts.get(configDraftKey(tenantId, configType))
  if (draft) renderConfigDraft(tenantId, configType, draft)
}

function selectConfigType(value: string | number | boolean | undefined) {
  const configType = value as TenantConfigType
  const tenantId = selectedTenantId.value
  if (!tenantId || !configTypes.includes(configType)) return
  selectedConfigTypeByTenant.set(tenantId, configType)
  const key = configDraftKey(tenantId, configType)
  const draft = configDrafts.get(key) ?? configDraftFromServer(configType, configs.value)
  configDrafts.set(key, draft)
  renderConfigDraft(tenantId, configType, draft)
}

function planLabel(plan: TenantPlan): string {
  return planLabels.value[plan] ?? t('common.unknown')
}

function tenantStatusLabel(status: TenantStatus): string {
  return tenantStatusLabels.value[status] ?? t('common.unknown')
}

function configTypeLabel(type: TenantConfigType): string {
  return configTypeLabels.value[type] ?? t('common.unknown')
}

function billStatusLabel(status: BillStatus): string {
  return billStatusLabels.value[status] ?? t('common.unknown')
}

function exportStatusLabel(status: ExportStatus): string {
  return exportStatusLabels.value[status] ?? t('common.unknown')
}

function exportTypeLabel(type: ExportType): string {
  return exportTypeLabels.value[type] ?? t('common.unknown')
}

function tenantStatusType(status: TenantStatus): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'ACTIVE') return 'success'
  if (status === 'TRIAL' || status === 'DOWNGRADED') return 'warning'
  if (status === 'EXPIRED' || status === 'SUSPENDED') return 'danger'
  return 'info'
}

function statusIcon(status: string): Component {
  if (status === 'ACTIVE' || status === 'COMPLETED' || status === 'RECONCILED') {
    return CircleCheck
  }
  if (status === 'TRIAL' || status === 'REQUESTED' || status === 'GENERATED') {
    return Clock
  }
  if (
    status === 'EXPIRED' ||
    status === 'SUSPENDED' ||
    status === 'FAILED' ||
    status === 'DOWNGRADED'
  ) {
    return WarningFilled
  }
  return InfoFilled
}

function tenantDisplayName(name?: string): string {
  if (!name) return t('tenant.tenantDetails')
  return name === 'MonkeyShop Platform Tenant' ? t('tenant.platformTenant') : name
}

function isPending(key: string): boolean {
  return pendingKeys.value.has(key)
}

function isLifecyclePending(tenantId: number): boolean {
  return (
    isPending(`tenant:${tenantId}:renew`) || isPending(`tenant:${tenantId}:downgrade`)
  )
}

function setPending(key: string, value: boolean) {
  const next = new Set(pendingKeys.value)
  if (value) next.add(key)
  else next.delete(key)
  pendingKeys.value = next
}

function patchTenant(nextTenant: Tenant) {
  listState.cancel()
  const current = listState.data.value
  if (!current) return
  const index = current.tenants.findIndex((tenant) => tenant.id === nextTenant.id)
  if (index >= 0) current.tenants.splice(index, 1, nextTenant)
  else current.tenants.unshift(nextTenant)
}

async function loadTenantList() {
  const result = await listState.load(async () => {
    const nextDashboard = await tenantApi.tenantDashboard()
    if (!nextDashboard.tenants.length) {
      nextDashboard.tenants = await tenantApi.tenants()
    }
    return nextDashboard
  })
  if (!result) return
  tenantDashboardLastSuccessAt.value = new Date()
  if (!result.tenants.length) {
    selectedTenantId.value = undefined
    configState.reset()
    billState.reset()
    exportState.reset()
    await router.replace({ query: { ...route.query, tenant: undefined } })
    return
  }

  const requestedId = queryTenantId()
  const requestedTenant = result.tenants.find((tenant) => tenant.id === requestedId)
  const selected = requestedTenant ?? result.tenants[0]
  if (!selected) return
  if (requestedId !== selected.id) {
    await router.replace({ query: { ...route.query, tenant: String(selected.id) } })
  }
  if (selectedTenantId.value === selected.id) return
  await selectTenant(selected, { revealMobile: false, syncUrl: false })
}

async function loadTenantConfigs(tenantId: number, requestVersion = detailRequestVersion) {
  const result = await configState.load(() => tenantApi.tenantConfigs(tenantId), {
    preserveData: false,
  })
  if (result && selectedTenantId.value === tenantId && requestVersion === detailRequestVersion) {
    initializeTenantConfigDrafts(tenantId, result)
  }
}

async function loadTenantBills(tenantId: number, requestVersion = detailRequestVersion) {
  await billState.load(() => tenantApi.tenantBills(tenantId), {
    preserveData: false,
  })
  if (selectedTenantId.value !== tenantId || requestVersion !== detailRequestVersion) return
}

async function loadTenantExports(tenantId: number, requestVersion = detailRequestVersion) {
  await exportState.load(() => tenantApi.tenantExports(tenantId), {
    preserveData: false,
  })
  if (selectedTenantId.value !== tenantId || requestVersion !== detailRequestVersion) return
}

async function loadTenantDetails(tenantId: number) {
  const requestVersion = ++detailRequestVersion
  configState.reset()
  billState.reset()
  exportState.reset()
  await Promise.all([
    loadTenantConfigs(tenantId, requestVersion),
    loadTenantBills(tenantId, requestVersion),
    loadTenantExports(tenantId, requestVersion),
  ])
}

async function selectTenant(
  tenant: Tenant,
  options: { revealMobile?: boolean; syncUrl?: boolean } = {},
) {
  selectedTenantId.value = tenant.id
  prepareTenantConfigDraft(tenant.id)
  mobileDetailVisible.value = options.revealMobile ?? true
  if (options.syncUrl !== false && firstQueryValue(route.query.tenant) !== String(tenant.id)) {
    await router.replace({ query: { ...route.query, tenant: String(tenant.id) } })
  }
  await loadTenantDetails(tenant.id)
}

async function refreshAll() {
  await loadTenantList()
}

async function submitCreateTenant() {
  if (isPending('tenant:create')) return
  setPending('tenant:create', true)
  try {
    const valid = await createFormRef.value?.validate().catch(() => false)
    if (!valid) return
    const tenant = await tenantApi.createTenant({
      ...createForm,
      code: createForm.code.trim(),
      name: createForm.name.trim(),
      contactName: createForm.contactName.trim(),
      contactPhone: createForm.contactPhone.trim(),
    })
    patchTenant(tenant)
    createDialogOpen.value = false
    notify.success(t('tenant.tenantCreated'), { key: 'tenant:create:success' })
    createForm.code = ''
    createForm.name = ''
    createForm.contactName = ''
    createForm.contactPhone = ''
    await selectTenant(tenant)
  } catch (error) {
    notify.fromApiError(error, 'tenant.tenantCreateFailed')
  } finally {
    setPending('tenant:create', false)
  }
}

async function renewSelected() {
  const tenant = selectedTenant.value
  if (!tenant) return
  const key = `tenant:${tenant.id}:renew`
  if (isLifecyclePending(tenant.id)) return
  setPending(key, true)
  try {
    patchTenant(await tenantApi.renewTenant(tenant.id, { months: 12 }))
    notify.success(t('tenant.tenantRenewed'), { key: 'tenant:renew:success' })
  } catch (error) {
    notify.fromApiError(error, 'tenant.tenantRenewFailed')
  } finally {
    setPending(key, false)
  }
}

async function downgradeSelected() {
  const tenant = selectedTenant.value
  if (!tenant) return
  const key = `tenant:${tenant.id}:downgrade`
  if (isLifecyclePending(tenant.id)) return
  setPending(key, true)
  try {
    const confirmed = await notify.confirm({
      title: t('tenant.downgradeTitle'),
      content: t('tenant.downgradeConfirm', { name: tenantDisplayName(tenant.name) }),
      confirmText: t('tenant.downgrade'),
      type: 'warning',
    })
    if (!confirmed) return
    patchTenant(await tenantApi.downgradeTenant(tenant.id, { plan: 'STARTER' }))
    notify.success(t('tenant.tenantDowngraded'), { key: 'tenant:downgrade:success' })
  } catch (error) {
    notify.fromApiError(error, 'tenant.tenantDowngradeFailed')
  } finally {
    setPending(key, false)
  }
}

function parseSettings(settingsText = configForm.settingsText): Record<string, string> | null {
  configError.value = ''
  try {
    const parsed = JSON.parse(settingsText) as unknown
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error()
    return Object.fromEntries(
      Object.entries(parsed as Record<string, unknown>).map(([key, value]) => [key, String(value)]),
    )
  } catch {
    configError.value = t('tenant.configMustBeJson')
    return null
  }
}

async function saveConfig() {
  const tenantId = selectedTenantId.value
  const configType = activeConfigType.value
  const key = `tenant:${tenantId}:config`
  if (!tenantId || isPending(key)) return
  const snapshot = {
    provider: configForm.provider.trim(),
    enabled: configForm.enabled,
    settingsText: configForm.settingsText,
  }
  const settings = parseSettings(snapshot.settingsText)
  if (!settings) return
  if (!snapshot.provider) {
    configError.value = t('tenant.providerRequired')
    return
  }
  setPending(key, true)
  try {
    const saved = await tenantApi.upsertTenantConfig(tenantId, {
      configType,
      provider: snapshot.provider,
      enabled: snapshot.enabled,
      settings,
    })
    const savedDraft = configDraftFromServer(configType, [saved])
    configDrafts.set(configDraftKey(tenantId, configType), savedDraft)
    if (activeConfigTenantId.value === tenantId && activeConfigType.value === configType) {
      renderConfigDraft(tenantId, configType, savedDraft)
    }
    const rows = selectedTenantId.value === tenantId ? configState.data.value : undefined
    if (rows) {
      const index = rows.findIndex((row) => row.configType === saved.configType)
      if (index >= 0) rows.splice(index, 1, saved)
      else rows.unshift(saved)
    }
    notify.success(t('tenant.configSaved'), { key: 'tenant:config:success' })
  } catch (error) {
    notify.fromApiError(error, 'tenant.configSaveFailed')
  } finally {
    setPending(key, false)
  }
}

async function generateBill() {
  const tenantId = selectedTenantId.value
  const key = `tenant:${tenantId}:bill`
  if (!tenantId || isPending(key)) return
  billError.value = ''
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(billForm.billingMonth)) {
    billError.value = t('tenant.billingMonthInvalid')
    return
  }
  setPending(key, true)
  try {
    const bill = await tenantApi.generateTenantBill(tenantId, {
      billingMonth: billForm.billingMonth,
    })
    const rows = selectedTenantId.value === tenantId ? billState.data.value : undefined
    if (rows) {
      const index = rows.findIndex((row) => row.id === bill.id)
      if (index >= 0) rows.splice(index, 1, bill)
      else rows.unshift(bill)
    }
    notify.success(t('tenant.billGenerated'), { key: 'tenant:bill:success' })
  } catch (error) {
    notify.fromApiError(error, 'tenant.billGenerateFailed')
  } finally {
    setPending(key, false)
  }
}

async function requestExport() {
  const tenantId = selectedTenantId.value
  if (!tenantId) return
  const key = `tenant:${tenantId}:export`
  if (
    isPending(key) ||
    !(['FULL', 'ORDERS', 'USERS'] as string[]).includes(exportForm.exportType)
  ) {
    return
  }
  setPending(key, true)
  try {
    const job = await tenantApi.requestTenantExport(tenantId, {
      exportType: exportForm.exportType,
    })
    if (selectedTenantId.value === tenantId) {
      exportState.data.value?.unshift(job)
    }
    notify.success(t('tenant.exportSubmitted'), { key: 'tenant:export:success' })
  } catch (error) {
    notify.fromApiError(error, 'tenant.exportSubmitFailed')
  } finally {
    setPending(key, false)
  }
}

async function synchronizeTenantFromRoute() {
  if (!tenantList.value.length) return
  const requestedId = queryTenantId()
  const requestedTenant = tenantList.value.find((row) => row.id === requestedId)
  if (!requestedTenant) {
    const fallback = selectedTenant.value ?? tenantList.value[0]
    if (!fallback) return
    if (firstQueryValue(route.query.tenant) !== String(fallback.id)) {
      await router.replace({ query: { ...route.query, tenant: String(fallback.id) } })
    }
    if (selectedTenantId.value !== fallback.id) {
      await selectTenant(fallback, { syncUrl: false })
    }
    return
  }
  if (requestedTenant.id !== selectedTenantId.value) {
    await selectTenant(requestedTenant, { syncUrl: false })
  }
}

watch(
  () => [configForm.provider, configForm.enabled, configForm.settingsText] as const,
  () => {
    if (!syncingConfigForm) persistActiveConfigDraft()
  },
  { flush: 'sync' },
)

watch(
  () => route.query.tenant,
  () => {
    void synchronizeTenantFromRoute()
  },
)

onMounted(loadTenantList)
</script>

<template>
  <div class="route-view tenant-page">
    <PageHeader
      :eyebrow="t('tenant.operations')"
      :title="t('tenant.title')"
      :description="t('tenant.description')"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="listState.isLoading.value" @click="refreshAll">
          {{ t('tenant.refresh') }}
        </el-button>
      </template>
    </PageHeader>

    <AsyncStateView
      :status="listState.status.value"
      :error="listState.error.value"
      :preserve-content-on-error="Boolean(dashboard)"
      @retry="loadTenantList"
    >
      <MetricStrip v-if="dashboard" :items="metrics" />
      <p
        v-if="tenantDashboardLastSuccessAt"
        class="data-freshness"
        :class="{ 'is-stale': listState.status.value === 'error' }"
        data-testid="tenant-dashboard-last-success"
      >
        {{
          t('dashboard.lastUpdated', {
            time: formatSuccessfulRefresh(tenantDashboardLastSuccessAt),
          })
        }}
      </p>

      <div
        v-if="dashboard"
        class="tenant-workspace"
        :class="{ 'tenant-workspace--detail': mobileDetailVisible }"
      >
        <section class="tenant-master" :aria-labelledby="'tenant-list-title'">
          <div class="section-heading">
            <div>
              <h2 id="tenant-list-title">{{ t('tenant.tenantList') }}</h2>
              <p>{{ tenantList.length }}</p>
            </div>
            <el-button
              type="primary"
              :icon="Plus"
              :disabled="isPending('tenant:create')"
              @click="createDialogOpen = true"
            >
              {{ t('tenant.createTenant') }}
            </el-button>
          </div>
          <DataTableShell
            :aria-label="t('tenant.tenantList')"
            :empty="tenantList.length === 0"
            :busy="listState.status.value === 'updating'"
          >
            <template #empty>{{ t('tenant.noTenants') }}</template>
            <el-table :data="tenantList" row-key="id" :highlight-current-row="true">
              <el-table-column :label="t('tenant.name')" min-width="180">
                <template #default="{ row }">
                  <button
                    class="tenant-select-button"
                    :class="{ 'is-selected': row.id === selectedTenantId }"
                    :aria-label="t('tenant.openTenant', { name: tenantDisplayName(row.name) })"
                    @click="selectTenant(row)"
                  >
                    <strong>{{ tenantDisplayName(row.name) }}</strong>
                    <span>{{ row.code }}</span>
                  </button>
                </template>
              </el-table-column>
              <el-table-column :label="t('tenant.plan')" width="120">
                <template #default="{ row }">{{ planLabel(row.plan) }}</template>
              </el-table-column>
              <el-table-column :label="t('tenant.status')" width="130">
                <template #default="{ row }">
                  <el-tag :type="tenantStatusType(row.status)" effect="plain">
                    <span class="status-label">
                      <el-icon aria-hidden="true"
                        ><component :is="statusIcon(row.status)"
                      /></el-icon>
                      {{ tenantStatusLabel(row.status) }}
                    </span>
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </DataTableShell>
        </section>

        <section class="tenant-detail" :aria-labelledby="'tenant-detail-title'">
          <el-button
            class="tenant-back-button"
            text
            :icon="ArrowLeft"
            @click="mobileDetailVisible = false"
          >
            {{ t('tenant.backToList') }}
          </el-button>

          <div v-if="selectedTenant" class="section-heading tenant-detail-heading">
            <div>
              <h2 id="tenant-detail-title">{{ tenantDisplayName(selectedTenant.name) }}</h2>
              <p>{{ selectedTenant.code }} · {{ planLabel(selectedTenant.plan) }}</p>
            </div>
            <div class="tenant-actions">
              <el-tag :type="tenantStatusType(selectedTenant.status)" effect="plain">
                <span class="status-label">
                  <el-icon aria-hidden="true">
                    <component :is="statusIcon(selectedTenant.status)" />
                  </el-icon>
                  {{ tenantStatusLabel(selectedTenant.status) }}
                </span>
              </el-tag>
              <el-button
                :loading="isPending(`tenant:${selectedTenant.id}:renew`)"
                :disabled="isLifecyclePending(selectedTenant.id)"
                @click="renewSelected"
              >
                {{ t('tenant.renew') }}
              </el-button>
              <el-button
                type="warning"
                plain
                :loading="isPending(`tenant:${selectedTenant.id}:downgrade`)"
                :disabled="isLifecyclePending(selectedTenant.id)"
                @click="downgradeSelected"
              >
                {{ t('tenant.downgrade') }}
              </el-button>
            </div>
          </div>

          <div v-else class="tenant-placeholder" role="status">{{ t('tenant.selectTenant') }}</div>

          <div v-if="selectedTenant" class="tenant-detail-content">
            <el-tabs v-model="activeTab" class="tenant-tabs">
              <el-tab-pane :label="t('tenant.config')" name="config">
                <AsyncStateView
                  :status="configState.status.value"
                  :error="configState.error.value"
                  @retry="loadTenantConfigs(selectedTenant.id)"
                >
                  <div class="tenant-task-form">
                    <el-select
                      :model-value="configForm.configType"
                      :aria-label="t('tenant.type')"
                      :disabled="isPending(`tenant:${selectedTenant.id}:config`)"
                      @update:model-value="selectConfigType"
                    >
                      <el-option :label="t('tenant.configTypePayment')" value="PAYMENT" />
                      <el-option :label="t('tenant.configTypeLogistics')" value="LOGISTICS" />
                      <el-option :label="t('tenant.configTypeMarketing')" value="MARKETING" />
                      <el-option :label="t('tenant.configTypeRollout')" value="ROLLOUT" />
                    </el-select>
                    <el-input
                      v-model="configForm.provider"
                      :placeholder="t('tenant.provider')"
                      :disabled="isPending(`tenant:${selectedTenant.id}:config`)"
                      @input="configError = ''"
                    />
                    <el-input
                      v-model="configForm.settingsText"
                      type="textarea"
                      :rows="5"
                      resize="vertical"
                      :aria-label="t('tenant.settingsJson')"
                      :disabled="isPending(`tenant:${selectedTenant.id}:config`)"
                      @input="configError = ''"
                    />
                    <p v-if="configError" class="inline-form-error" role="alert">
                      {{ configError }}
                    </p>
                    <el-switch
                      v-model="configForm.enabled"
                      :aria-label="t('tenant.enabled')"
                      :active-text="t('tenant.enabled')"
                      :inactive-text="t('tenant.disabled')"
                      :disabled="isPending(`tenant:${selectedTenant.id}:config`)"
                    />
                    <el-button
                      type="primary"
                      :loading="isPending(`tenant:${selectedTenant.id}:config`)"
                      :disabled="isPending(`tenant:${selectedTenant.id}:config`)"
                      @click="saveConfig"
                    >
                      {{ t('tenant.saveConfig') }}
                    </el-button>
                  </div>
                  <DataTableShell :empty="configs.length === 0" :aria-label="t('tenant.config')">
                    <template #empty>{{ t('tenant.noConfigs') }}</template>
                    <el-table :data="configs" row-key="id" size="small">
                      <el-table-column :label="t('tenant.type')" width="130">
                        <template #default="{ row }">{{
                          configTypeLabel(row.configType)
                        }}</template>
                      </el-table-column>
                      <el-table-column
                        prop="provider"
                        :label="t('tenant.provider')"
                        min-width="150"
                      />
                      <el-table-column :label="t('tenant.status')" width="120">
                        <template #default="{ row }">
                          <span class="status-label">
                            <el-icon aria-hidden="true">
                              <component :is="statusIcon(row.enabled ? 'ACTIVE' : 'SUSPENDED')" />
                            </el-icon>
                            {{ row.enabled ? t('tenant.enabled') : t('tenant.disabled') }}
                          </span>
                        </template>
                      </el-table-column>
                      <el-table-column
                        prop="updatedAt"
                        :label="t('tenant.updatedAt')"
                        min-width="180"
                      />
                    </el-table>
                  </DataTableShell>
                </AsyncStateView>
              </el-tab-pane>

              <el-tab-pane :label="t('tenant.bill')" name="bill">
                <AsyncStateView
                  :status="billState.status.value"
                  :error="billState.error.value"
                  @retry="loadTenantBills(selectedTenant.id)"
                >
                  <div class="tenant-task-form tenant-task-form--inline">
                    <el-input
                      v-model="billForm.billingMonth"
                      :placeholder="t('tenant.billingMonth')"
                      :aria-label="t('tenant.billingMonth')"
                      :disabled="isPending(`tenant:${selectedTenant.id}:bill`)"
                      @input="billError = ''"
                    />
                    <el-button
                      type="primary"
                      :loading="isPending(`tenant:${selectedTenant.id}:bill`)"
                      :disabled="isPending(`tenant:${selectedTenant.id}:bill`)"
                      @click="generateBill"
                    >
                      {{ t('tenant.generateBill') }}
                    </el-button>
                    <p v-if="billError" class="inline-form-error" role="alert">
                      {{ billError }}
                    </p>
                  </div>
                  <DataTableShell :empty="bills.length === 0" :aria-label="t('tenant.bill')">
                    <template #empty>{{ t('tenant.noBills') }}</template>
                    <el-table :data="bills" row-key="id" size="small">
                      <el-table-column prop="billingMonth" :label="t('tenant.month')" width="110" />
                      <el-table-column
                        prop="orderCount"
                        :label="t('tenant.orderCount')"
                        width="110"
                      />
                      <el-table-column :label="t('tenant.totalAmount')" min-width="130">
                        <template #default="{ row }">{{ money(row.totalAmount) }}</template>
                      </el-table-column>
                      <el-table-column :label="t('tenant.status')" width="140">
                        <template #default="{ row }">
                          <span class="status-label">
                            <el-icon aria-hidden="true">
                              <component :is="statusIcon(row.status)" />
                            </el-icon>
                            {{ billStatusLabel(row.status) }}
                          </span>
                        </template>
                      </el-table-column>
                    </el-table>
                  </DataTableShell>
                </AsyncStateView>
              </el-tab-pane>

              <el-tab-pane :label="t('tenant.export')" name="export">
                <AsyncStateView
                  :status="exportState.status.value"
                  :error="exportState.error.value"
                  @retry="loadTenantExports(selectedTenant.id)"
                >
                  <div class="tenant-task-form tenant-task-form--inline">
                    <el-select
                      v-model="exportForm.exportType"
                      :aria-label="t('tenant.type')"
                      :disabled="isPending(`tenant:${selectedTenant.id}:export`)"
                    >
                      <el-option :label="t('tenant.exportFull')" value="FULL" />
                      <el-option :label="t('tenant.exportOrders')" value="ORDERS" />
                      <el-option :label="t('tenant.exportUsers')" value="USERS" />
                    </el-select>
                    <el-button
                      type="primary"
                      :loading="isPending(`tenant:${selectedTenant.id}:export`)"
                      :disabled="isPending(`tenant:${selectedTenant.id}:export`)"
                      @click="requestExport"
                    >
                      {{ t('tenant.submitExport') }}
                    </el-button>
                  </div>
                  <DataTableShell :empty="exports.length === 0" :aria-label="t('tenant.export')">
                    <template #empty>{{ t('tenant.noExports') }}</template>
                    <el-table :data="exports" row-key="id" size="small">
                      <el-table-column :label="t('tenant.type')" width="120">
                        <template #default="{ row }">{{
                          exportTypeLabel(row.exportType)
                        }}</template>
                      </el-table-column>
                      <el-table-column :label="t('tenant.status')" width="140">
                        <template #default="{ row }">
                          <span class="status-label">
                            <el-icon aria-hidden="true">
                              <component :is="statusIcon(row.status)" />
                            </el-icon>
                            {{ exportStatusLabel(row.status) }}
                          </span>
                        </template>
                      </el-table-column>
                      <el-table-column :label="t('tenant.archiveAvailability')" min-width="170">
                        <template #default="{ row }">
                          {{
                            row.status === 'COMPLETED' && row.encryptedArchivePath
                              ? t('tenant.exportReady')
                              : t('tenant.exportUnavailable')
                          }}
                        </template>
                      </el-table-column>
                      <el-table-column
                        prop="requestedAt"
                        :label="t('tenant.requestedAt')"
                        min-width="180"
                      />
                    </el-table>
                  </DataTableShell>
                </AsyncStateView>
              </el-tab-pane>
            </el-tabs>
          </div>
        </section>
      </div>
    </AsyncStateView>

    <el-dialog
      v-model="createDialogOpen"
      :title="t('tenant.createTenantTitle')"
      width="min(520px, 92vw)"
      :close-on-click-modal="!isPending('tenant:create')"
      :close-on-press-escape="!isPending('tenant:create')"
    >
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-position="top">
        <div class="create-form-grid">
          <el-form-item :label="t('tenant.code')" prop="code">
            <el-input v-model="createForm.code" :placeholder="t('tenant.merchantCode')" />
          </el-form-item>
          <el-form-item :label="t('tenant.name')" prop="name">
            <el-input v-model="createForm.name" :placeholder="t('tenant.merchantName')" />
          </el-form-item>
          <el-form-item :label="t('tenant.plan')" prop="plan">
            <el-select v-model="createForm.plan">
              <el-option :label="t('tenant.planStarter')" value="STARTER" />
              <el-option :label="t('tenant.planGrowth')" value="GROWTH" />
              <el-option :label="t('tenant.planEnterprise')" value="ENTERPRISE" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('tenant.months')" prop="months">
            <el-input-number v-model="createForm.months" :min="1" :max="36" />
          </el-form-item>
          <el-form-item :label="t('tenant.contactName')">
            <el-input v-model="createForm.contactName" />
          </el-form-item>
          <el-form-item :label="t('tenant.contactPhone')">
            <el-input v-model="createForm.contactPhone" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button :disabled="isPending('tenant:create')" @click="createDialogOpen = false">
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="isPending('tenant:create')"
          :disabled="isPending('tenant:create')"
          @click="submitCreateTenant"
        >
          {{ t('tenant.create') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tenant-page {
  display: grid;
  gap: var(--space-5);
  min-width: 0;
}

.data-freshness {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  text-align: right;
}

.data-freshness.is-stale {
  color: var(--color-danger);
}

.tenant-workspace {
  display: grid;
  grid-template-columns: minmax(360px, 0.9fr) minmax(0, 1.35fr);
  gap: var(--space-6);
  min-width: 0;
}

.tenant-master,
.tenant-detail {
  display: grid;
  align-content: start;
  gap: var(--space-4);
  min-width: 0;
}

.tenant-detail {
  padding-left: var(--space-6);
  border-left: 1px solid var(--color-line);
}

.section-heading,
.tenant-actions,
.tenant-task-form--inline {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.section-heading {
  justify-content: space-between;
}

.section-heading h2,
.section-heading p,
.inline-form-error {
  margin: 0;
}

.section-heading h2 {
  font-size: var(--text-lg);
}

.section-heading p {
  margin-top: var(--space-1);
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.tenant-actions {
  justify-content: flex-end;
  flex-wrap: wrap;
}

.status-label {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  white-space: nowrap;
}

.tenant-detail-content {
  min-width: 0;
}

.tenant-select-button {
  display: grid;
  gap: var(--space-1);
  width: 100%;
  padding: var(--space-1);
  border: 0;
  color: var(--color-text);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.tenant-select-button span {
  color: var(--color-text-muted);
  font-size: var(--text-xs);
}

.tenant-select-button:hover strong,
.tenant-select-button.is-selected strong {
  color: var(--color-brand);
}

.tenant-select-button:focus-visible {
  outline: 2px solid var(--focus-ring);
  outline-offset: 2px;
}

.tenant-task-form {
  display: grid;
  grid-template-columns: minmax(150px, 0.7fr) minmax(180px, 1fr);
  gap: var(--space-3);
  margin-bottom: var(--space-4);
  padding-block: var(--space-3);
}

.tenant-task-form :deep(.el-textarea),
.inline-form-error {
  grid-column: 1 / -1;
}

.tenant-task-form--inline {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-start;
}

.tenant-task-form--inline :deep(.el-input),
.tenant-task-form--inline :deep(.el-select) {
  width: min(260px, 100%);
}

.inline-form-error {
  color: var(--color-danger);
  font-size: var(--text-sm);
}

.tenant-task-form--inline .inline-form-error {
  flex-basis: 100%;
}

.tenant-tabs,
.tenant-placeholder {
  min-width: 0;
}

.tenant-placeholder {
  padding: var(--space-8) 0;
  color: var(--color-text-muted);
  text-align: center;
}

.tenant-back-button {
  display: none;
  justify-self: start;
}

.create-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--space-4);
}

@media (max-width: 980px) {
  .tenant-workspace {
    grid-template-columns: minmax(280px, 0.85fr) minmax(0, 1.15fr);
    gap: var(--space-4);
  }

  .tenant-detail {
    padding-left: var(--space-4);
  }
}

@media (max-width: 760px) {
  .tenant-workspace {
    display: block;
  }

  .tenant-detail {
    display: none;
    padding-left: 0;
    border-left: 0;
  }

  .tenant-workspace--detail .tenant-master {
    display: none;
  }

  .tenant-workspace--detail .tenant-detail,
  .tenant-back-button {
    display: grid;
  }

  .tenant-detail-heading,
  .tenant-task-form,
  .tenant-task-form--inline,
  .create-form-grid {
    grid-template-columns: 1fr;
  }

  .tenant-detail-heading,
  .tenant-task-form--inline {
    align-items: stretch;
    flex-direction: column;
  }

  .tenant-task-form--inline :deep(.el-input),
  .tenant-task-form--inline :deep(.el-select),
  .tenant-task-form--inline :deep(.el-button) {
    width: 100%;
  }
}
</style>

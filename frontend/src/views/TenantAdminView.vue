<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as tenantApi from '@/api/tenant'
import AppShell from '@/components/AppShell.vue'
import type {
  Tenant,
  TenantBill,
  TenantConfig,
  TenantConfigType,
  TenantDashboard,
  TenantExportJob,
  TenantPlan,
} from '@/types'

const loading = ref(false)
const dashboard = ref<TenantDashboard>()
const tenantList = ref<Tenant[]>([])
const selectedTenantId = ref<number>()
const configs = ref<TenantConfig[]>([])
const bills = ref<TenantBill[]>([])
const exports = ref<TenantExportJob[]>([])

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
  settingsText: '{ "merchantId": "demo", "canaryWeight": "10" }',
})

const billForm = reactive({
  billingMonth: new Date().toISOString().slice(0, 7),
})

const exportForm = reactive({
  exportType: 'FULL',
})

const selectedTenant = computed(() =>
  tenantList.value.find((tenant) => tenant.id === selectedTenantId.value),
)

async function loadAll() {
  loading.value = true
  try {
    dashboard.value = await tenantApi.tenantDashboard()
    tenantList.value = dashboard.value.tenants.length
      ? dashboard.value.tenants
      : await tenantApi.tenants()
    if (!selectedTenantId.value && tenantList.value.length) {
      selectedTenantId.value = tenantList.value[0].id
    }
    await loadTenantDetails()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load tenants')
  } finally {
    loading.value = false
  }
}

async function loadTenantDetails() {
  if (!selectedTenantId.value) {
    configs.value = []
    bills.value = []
    exports.value = []
    return
  }
  const tenantId = selectedTenantId.value
  const [configRows, billRows, exportRows] = await Promise.all([
    tenantApi.tenantConfigs(tenantId),
    tenantApi.tenantBills(tenantId),
    tenantApi.tenantExports(tenantId),
  ])
  configs.value = configRows
  bills.value = billRows
  exports.value = exportRows
}

async function selectTenant(row: Tenant) {
  selectedTenantId.value = row.id
  await loadTenantDetails()
}

async function createTenant() {
  const tenant = await tenantApi.createTenant({ ...createForm })
  selectedTenantId.value = tenant.id
  ElMessage.success('Tenant created')
  createForm.code = ''
  createForm.name = ''
  createForm.contactName = ''
  createForm.contactPhone = ''
  await loadAll()
}

async function renewSelected() {
  if (!selectedTenantId.value) return
  await tenantApi.renewTenant(selectedTenantId.value, { months: 12 })
  ElMessage.success('Tenant renewed')
  await loadAll()
}

async function downgradeSelected() {
  if (!selectedTenantId.value) return
  await tenantApi.downgradeTenant(selectedTenantId.value, { plan: 'STARTER' })
  ElMessage.success('Tenant downgraded')
  await loadAll()
}

async function saveConfig() {
  if (!selectedTenantId.value) return
  let settings: Record<string, string>
  try {
    settings = JSON.parse(configForm.settingsText) as Record<string, string>
  } catch {
    ElMessage.error('Settings must be valid JSON')
    return
  }
  await tenantApi.upsertTenantConfig(selectedTenantId.value, {
    configType: configForm.configType,
    provider: configForm.provider,
    enabled: configForm.enabled,
    settings,
  })
  ElMessage.success('Config saved')
  await loadTenantDetails()
}

async function generateBill() {
  if (!selectedTenantId.value) return
  await tenantApi.generateTenantBill(selectedTenantId.value, {
    billingMonth: billForm.billingMonth,
  })
  ElMessage.success('Bill generated')
  await loadTenantDetails()
}

async function requestExport() {
  if (!selectedTenantId.value) return
  await tenantApi.requestTenantExport(selectedTenantId.value, { exportType: exportForm.exportType })
  ElMessage.success('Export requested')
  await loadTenantDetails()
}

onMounted(loadAll)
</script>

<template>
  <AppShell>
    <section v-loading="loading" class="tenant-page">
      <header class="tenant-header">
        <div>
          <p class="eyebrow">SaaS Ops</p>
          <h1>Tenant Command Center</h1>
        </div>
        <el-button type="primary" @click="loadAll">Refresh</el-button>
      </header>

      <div class="tenant-metrics">
        <div>
          <span>Active tenants</span>
          <strong>{{ dashboard?.activeTenants ?? 0 }}</strong>
        </div>
        <div>
          <span>Expired tenants</span>
          <strong>{{ dashboard?.expiredTenants ?? 0 }}</strong>
        </div>
        <div>
          <span>Monthly orders</span>
          <strong>{{ dashboard?.currentMonthOrders ?? 0 }}</strong>
        </div>
        <div>
          <span>Revenue</span>
          <strong>{{ dashboard?.currentMonthRevenue ?? 0 }}</strong>
        </div>
      </div>

      <div class="tenant-workspace">
        <section class="tenant-panel">
          <div class="panel-title">
            <h2>Tenants</h2>
            <div class="tenant-actions">
              <el-button size="small" @click="renewSelected">Renew</el-button>
              <el-button size="small" type="warning" @click="downgradeSelected">
                Downgrade
              </el-button>
            </div>
          </div>

          <el-table :data="tenantList" highlight-current-row @row-click="selectTenant">
            <el-table-column prop="code" label="Code" min-width="120" />
            <el-table-column prop="name" label="Name" min-width="160" />
            <el-table-column prop="plan" label="Plan" width="120" />
            <el-table-column prop="status" label="Status" width="130" />
            <el-table-column prop="maskedContactPhone" label="Phone" width="140" />
          </el-table>

          <div class="compact-form">
            <el-input v-model="createForm.code" placeholder="merchant-code" />
            <el-input v-model="createForm.name" placeholder="Merchant name" />
            <el-select v-model="createForm.plan">
              <el-option label="Starter" value="STARTER" />
              <el-option label="Growth" value="GROWTH" />
              <el-option label="Enterprise" value="ENTERPRISE" />
            </el-select>
            <el-input v-model="createForm.contactName" placeholder="Contact" />
            <el-input v-model="createForm.contactPhone" placeholder="Phone" />
            <el-input-number v-model="createForm.months" :min="1" :max="36" />
            <el-button type="primary" @click="createTenant">Create</el-button>
          </div>
        </section>

        <section class="tenant-panel">
          <div class="panel-title">
            <h2>{{ selectedTenant?.name || 'Tenant detail' }}</h2>
            <el-tag v-if="selectedTenant">{{ selectedTenant.status }}</el-tag>
          </div>

          <div class="tenant-forms">
            <div class="tenant-form-block">
              <h3>Config</h3>
              <el-select v-model="configForm.configType">
                <el-option label="Payment" value="PAYMENT" />
                <el-option label="Logistics" value="LOGISTICS" />
                <el-option label="Marketing" value="MARKETING" />
                <el-option label="Rollout" value="ROLLOUT" />
              </el-select>
              <el-input v-model="configForm.provider" placeholder="provider" />
              <el-input v-model="configForm.settingsText" type="textarea" :rows="4" resize="none" />
              <el-switch v-model="configForm.enabled" active-text="Enabled" />
              <el-button type="primary" @click="saveConfig">Save config</el-button>
            </div>

            <div class="tenant-form-block">
              <h3>Billing</h3>
              <el-input v-model="billForm.billingMonth" placeholder="yyyy-MM" />
              <el-button type="primary" @click="generateBill">Generate bill</el-button>
            </div>

            <div class="tenant-form-block">
              <h3>Export</h3>
              <el-select v-model="exportForm.exportType">
                <el-option label="Full" value="FULL" />
                <el-option label="Orders" value="ORDERS" />
                <el-option label="Users" value="USERS" />
              </el-select>
              <el-button type="primary" @click="requestExport">Request export</el-button>
            </div>
          </div>

          <el-tabs class="tenant-tabs">
            <el-tab-pane label="Configs">
              <el-table :data="configs">
                <el-table-column prop="configType" label="Type" width="120" />
                <el-table-column prop="provider" label="Provider" width="140" />
                <el-table-column prop="enabled" label="Enabled" width="100" />
                <el-table-column prop="updatedAt" label="Updated" min-width="180" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="Bills">
              <el-table :data="bills">
                <el-table-column prop="billingMonth" label="Month" width="110" />
                <el-table-column prop="orderCount" label="Orders" width="100" />
                <el-table-column prop="totalAmount" label="Total" width="120" />
                <el-table-column prop="paymentAmount" label="Payment" width="120" />
                <el-table-column prop="status" label="Status" width="130" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="Exports">
              <el-table :data="exports">
                <el-table-column prop="exportType" label="Type" width="110" />
                <el-table-column prop="status" label="Status" width="130" />
                <el-table-column prop="encryptedArchivePath" label="Archive" min-width="240" />
                <el-table-column prop="requestedAt" label="Requested" min-width="180" />
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </section>
      </div>
    </section>
  </AppShell>
</template>

<style scoped>
.tenant-page {
  display: grid;
  gap: 20px;
}

.tenant-header,
.panel-title,
.tenant-actions {
  display: flex;
  align-items: center;
}

.tenant-header,
.panel-title {
  justify-content: space-between;
  gap: 16px;
}

.tenant-header h1,
.tenant-panel h2,
.tenant-form-block h3 {
  margin: 0;
}

.tenant-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.tenant-metrics > div,
.tenant-panel {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  background: var(--surface-color);
}

.tenant-metrics > div {
  display: grid;
  gap: 8px;
  padding: 16px;
}

.tenant-metrics span {
  color: var(--muted-color);
  font-size: 13px;
}

.tenant-metrics strong {
  font-size: 24px;
}

.tenant-workspace {
  display: grid;
  grid-template-columns: minmax(360px, 0.95fr) minmax(480px, 1.25fr);
  gap: 16px;
}

.tenant-panel {
  display: grid;
  gap: 16px;
  padding: 16px;
}

.tenant-actions,
.compact-form,
.tenant-forms,
.tenant-form-block {
  gap: 10px;
}

.compact-form,
.tenant-forms,
.tenant-form-block {
  display: grid;
}

.tenant-forms {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.tenant-tabs {
  min-width: 0;
}

@media (max-width: 1100px) {
  .tenant-workspace,
  .tenant-forms,
  .tenant-metrics {
    grid-template-columns: 1fr;
  }
}
</style>

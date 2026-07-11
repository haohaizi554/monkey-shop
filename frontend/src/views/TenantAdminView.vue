<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as tenantApi from '@/api/tenant'
import type {
  Tenant,
  TenantBill,
  TenantConfig,
  TenantDashboard,
  TenantExportJob,
  TenantPlan,
  TenantConfigType,
} from '@/types'

type TenantStatus = Tenant['status']
type BillStatus = TenantBill['status']
type ExportStatus = TenantExportJob['status']

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

const planLabels: Record<TenantPlan, string> = {
  STARTER: '基础版',
  GROWTH: '增长版',
  ENTERPRISE: '企业版',
}

const tenantStatusLabels: Record<TenantStatus, string> = {
  TRIAL: '试用中',
  ACTIVE: '生效中',
  EXPIRED: '已过期',
  DOWNGRADED: '已降级',
  SUSPENDED: '已暂停',
}

const configTypeLabels: Record<TenantConfigType, string> = {
  PAYMENT: '支付',
  LOGISTICS: '物流',
  MARKETING: '营销',
  ROLLOUT: '发布',
}

const billStatusLabels: Record<BillStatus, string> = {
  GENERATED: '已生成',
  RECONCILED: '已对账',
  SUSPENDED: '已挂起',
}

const exportStatusLabels: Record<ExportStatus, string> = {
  REQUESTED: '处理中',
  COMPLETED: '已完成',
  FAILED: '失败',
}

function planLabel(plan: TenantPlan): string {
  return planLabels[plan] ?? plan
}

function tenantStatusLabel(status: TenantStatus): string {
  return tenantStatusLabels[status] ?? status
}

function configTypeLabel(type: TenantConfigType): string {
  return configTypeLabels[type] ?? type
}

function billStatusLabel(status: BillStatus): string {
  return billStatusLabels[status] ?? status
}

function exportStatusLabel(status: ExportStatus): string {
  return exportStatusLabels[status] ?? status
}

function tenantDisplayName(name?: string): string {
  if (!name) {
    return '租户详情'
  }
  return name === 'MonkeyShop Platform Tenant' ? 'MonkeyShop 平台租户' : name
}

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
    ElMessage.error(error instanceof Error ? error.message : '租户数据加载失败')
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
  ElMessage.success('租户已创建')
  createForm.code = ''
  createForm.name = ''
  createForm.contactName = ''
  createForm.contactPhone = ''
  await loadAll()
}

async function renewSelected() {
  if (!selectedTenantId.value) return
  await tenantApi.renewTenant(selectedTenantId.value, { months: 12 })
  ElMessage.success('租户已续费')
  await loadAll()
}

async function downgradeSelected() {
  if (!selectedTenantId.value) return
  await tenantApi.downgradeTenant(selectedTenantId.value, { plan: 'STARTER' })
  ElMessage.success('租户已降级')
  await loadAll()
}

async function saveConfig() {
  if (!selectedTenantId.value) return
  let settings: Record<string, string>
  try {
    settings = JSON.parse(configForm.settingsText) as Record<string, string>
  } catch {
    ElMessage.error('配置内容必须是合法 JSON')
    return
  }
  await tenantApi.upsertTenantConfig(selectedTenantId.value, {
    configType: configForm.configType,
    provider: configForm.provider,
    enabled: configForm.enabled,
    settings,
  })
  ElMessage.success('租户配置已保存')
  await loadTenantDetails()
}

async function generateBill() {
  if (!selectedTenantId.value) return
  await tenantApi.generateTenantBill(selectedTenantId.value, {
    billingMonth: billForm.billingMonth,
  })
  ElMessage.success('账单已生成')
  await loadTenantDetails()
}

async function requestExport() {
  if (!selectedTenantId.value) return
  await tenantApi.requestTenantExport(selectedTenantId.value, { exportType: exportForm.exportType })
  ElMessage.success('导出任务已提交')
  await loadTenantDetails()
}

onMounted(loadAll)
</script>

<template>
  <div class="route-view">
    <section v-loading="loading" class="tenant-page">
      <header class="tenant-header">
        <div>
          <p class="eyebrow">租户运营</p>
          <h1>租户指挥台</h1>
        </div>
        <el-button type="primary" @click="loadAll">刷新</el-button>
      </header>

      <div class="tenant-metrics">
        <div>
          <span>有效租户</span>
          <strong>{{ dashboard?.activeTenants ?? 0 }}</strong>
        </div>
        <div>
          <span>过期租户</span>
          <strong>{{ dashboard?.expiredTenants ?? 0 }}</strong>
        </div>
        <div>
          <span>本月订单</span>
          <strong>{{ dashboard?.currentMonthOrders ?? 0 }}</strong>
        </div>
        <div>
          <span>本月收入</span>
          <strong>{{ dashboard?.currentMonthRevenue ?? 0 }}</strong>
        </div>
      </div>

      <div class="tenant-workspace">
        <section class="tenant-panel">
          <div class="panel-title">
            <h2>租户列表</h2>
            <div class="tenant-actions">
              <el-button size="small" @click="renewSelected">续费</el-button>
              <el-button size="small" type="warning" @click="downgradeSelected"> 降级 </el-button>
            </div>
          </div>

          <el-table :data="tenantList" highlight-current-row @row-click="selectTenant">
            <el-table-column prop="code" label="编码" min-width="120" />
            <el-table-column label="名称" min-width="160">
              <template #default="{ row }">{{ tenantDisplayName(row.name) }}</template>
            </el-table-column>
            <el-table-column label="版本" width="120">
              <template #default="{ row }">{{ planLabel(row.plan) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">{{ tenantStatusLabel(row.status) }}</template>
            </el-table-column>
            <el-table-column prop="maskedContactPhone" label="电话" width="140" />
          </el-table>

          <div class="compact-form">
            <el-input v-model="createForm.code" placeholder="merchant-code" />
            <el-input v-model="createForm.name" placeholder="商户名称" />
            <el-select v-model="createForm.plan">
              <el-option label="基础版" value="STARTER" />
              <el-option label="增长版" value="GROWTH" />
              <el-option label="企业版" value="ENTERPRISE" />
            </el-select>
            <el-input v-model="createForm.contactName" placeholder="联系人" />
            <el-input v-model="createForm.contactPhone" placeholder="联系电话" />
            <el-input-number v-model="createForm.months" :min="1" :max="36" />
            <el-button type="primary" @click="createTenant">创建</el-button>
          </div>
        </section>

        <section class="tenant-panel">
          <div class="panel-title">
            <h2>{{ tenantDisplayName(selectedTenant?.name) }}</h2>
            <el-tag v-if="selectedTenant">{{ tenantStatusLabel(selectedTenant.status) }}</el-tag>
          </div>

          <div class="tenant-forms">
            <div class="tenant-form-block">
              <h3>配置</h3>
              <el-select v-model="configForm.configType">
                <el-option label="支付" value="PAYMENT" />
                <el-option label="物流" value="LOGISTICS" />
                <el-option label="营销" value="MARKETING" />
                <el-option label="发布" value="ROLLOUT" />
              </el-select>
              <el-input v-model="configForm.provider" placeholder="provider" />
              <el-input v-model="configForm.settingsText" type="textarea" :rows="4" resize="none" />
              <el-switch v-model="configForm.enabled" active-text="已启用" inactive-text="已停用" />
              <el-button type="primary" @click="saveConfig">保存配置</el-button>
            </div>

            <div class="tenant-form-block">
              <h3>账单</h3>
              <el-input v-model="billForm.billingMonth" placeholder="yyyy-MM" />
              <el-button type="primary" @click="generateBill">生成账单</el-button>
            </div>

            <div class="tenant-form-block">
              <h3>导出</h3>
              <el-select v-model="exportForm.exportType">
                <el-option label="全量" value="FULL" />
                <el-option label="订单" value="ORDERS" />
                <el-option label="用户" value="USERS" />
              </el-select>
              <el-button type="primary" @click="requestExport">提交导出</el-button>
            </div>
          </div>

          <el-tabs class="tenant-tabs">
            <el-tab-pane label="配置">
              <el-table :data="configs">
                <el-table-column label="类型" width="120">
                  <template #default="{ row }">{{ configTypeLabel(row.configType) }}</template>
                </el-table-column>
                <el-table-column prop="provider" label="服务商" width="140" />
                <el-table-column label="启用" width="100">
                  <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
                </el-table-column>
                <el-table-column prop="updatedAt" label="更新时间" min-width="180" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="账单">
              <el-table :data="bills">
                <el-table-column prop="billingMonth" label="月份" width="110" />
                <el-table-column prop="orderCount" label="订单数" width="100" />
                <el-table-column prop="totalAmount" label="合计" width="120" />
                <el-table-column prop="paymentAmount" label="支付额" width="120" />
                <el-table-column label="状态" width="130">
                  <template #default="{ row }">{{ billStatusLabel(row.status) }}</template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="导出">
              <el-table :data="exports">
                <el-table-column prop="exportType" label="类型" width="110" />
                <el-table-column label="状态" width="130">
                  <template #default="{ row }">{{ exportStatusLabel(row.status) }}</template>
                </el-table-column>
                <el-table-column prop="encryptedArchivePath" label="归档路径" min-width="240" />
                <el-table-column prop="requestedAt" label="提交时间" min-width="180" />
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </section>
      </div>
    </section>
  </div>
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

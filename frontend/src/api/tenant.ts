import { request } from '@/api/http'
import type {
  Tenant,
  TenantBill,
  TenantBillGenerateRequest,
  TenantConfig,
  TenantConfigRequest,
  TenantCreateRequest,
  TenantDashboard,
  TenantDowngradeRequest,
  TenantExportJob as TenantExportJobContract,
  TenantExportRequest,
  TenantRenewRequest,
} from '@/types'

export interface TenantExportJob extends TenantExportJobContract {
  artifactDownloadUri?: string | null
}

export function tenantExportDownloadUri(job: TenantExportJob): string | undefined {
  if (job.status !== 'SUCCEEDED' || !job.artifactAvailable) return undefined

  const tenantId = String(job.tenantId)
  const jobId = String(job.id)
  if (!/^\d+$/.test(tenantId) || !/^\d+$/.test(jobId)) return undefined

  const expected = `/api/v1/tenants/${tenantId}/exports/${jobId}/artifact`
  return job.artifactDownloadUri === expected ? expected : undefined
}

export function tenantDashboard(): Promise<TenantDashboard> {
  return request<TenantDashboard>({ url: '/tenants/dashboard' })
}

export function tenants(): Promise<Tenant[]> {
  return request<Tenant[]>({ url: '/tenants' })
}

export function createTenant(payload: TenantCreateRequest): Promise<Tenant> {
  return request<Tenant>({ url: '/tenants', method: 'POST', data: payload })
}

export function renewTenant(tenantId: number, payload: TenantRenewRequest): Promise<Tenant> {
  return request<Tenant>({ url: `/tenants/${tenantId}/renew`, method: 'POST', data: payload })
}

export function downgradeTenant(
  tenantId: number,
  payload: TenantDowngradeRequest,
): Promise<Tenant> {
  return request<Tenant>({ url: `/tenants/${tenantId}/downgrade`, method: 'POST', data: payload })
}

export function tenantConfigs(tenantId: number): Promise<TenantConfig[]> {
  return request<TenantConfig[]>({ url: `/tenants/${tenantId}/configs` })
}

export function upsertTenantConfig(
  tenantId: number,
  payload: TenantConfigRequest,
): Promise<TenantConfig> {
  return request<TenantConfig>({
    url: `/tenants/${tenantId}/configs`,
    method: 'PUT',
    data: payload,
  })
}

export function generateTenantBill(
  tenantId: number,
  payload: TenantBillGenerateRequest,
): Promise<TenantBill> {
  return request<TenantBill>({ url: `/tenants/${tenantId}/bills`, method: 'POST', data: payload })
}

export function tenantBills(tenantId: number): Promise<TenantBill[]> {
  return request<TenantBill[]>({ url: `/tenants/${tenantId}/bills` })
}

export function requestTenantExport(
  tenantId: number,
  payload: TenantExportRequest,
): Promise<TenantExportJob> {
  return request<TenantExportJob>({
    url: `/tenants/${tenantId}/exports`,
    method: 'POST',
    data: payload,
  })
}

export function tenantExports(tenantId: number): Promise<TenantExportJob[]> {
  return request<TenantExportJob[]>({ url: `/tenants/${tenantId}/exports` })
}

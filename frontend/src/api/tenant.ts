import { request } from '@/api/http'
import { normalizeApiId, parsePositiveApiId, type ApiId } from '@/api/ids'
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

  const tenantId = parsePositiveApiId(job.tenantId)
  const jobId = parsePositiveApiId(job.id)
  if (tenantId === undefined || jobId === undefined) return undefined

  const expected = `/api/v1/tenants/${normalizeApiId(tenantId)}/exports/${normalizeApiId(jobId)}/artifact`
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

export function renewTenant(tenantId: ApiId, payload: TenantRenewRequest): Promise<Tenant> {
  return request<Tenant>({ url: `/tenants/${tenantId}/renew`, method: 'POST', data: payload })
}

export function downgradeTenant(tenantId: ApiId, payload: TenantDowngradeRequest): Promise<Tenant> {
  return request<Tenant>({ url: `/tenants/${tenantId}/downgrade`, method: 'POST', data: payload })
}

export function tenantConfigs(tenantId: ApiId): Promise<TenantConfig[]> {
  return request<TenantConfig[]>({ url: `/tenants/${tenantId}/configs` })
}

export function upsertTenantConfig(
  tenantId: ApiId,
  payload: TenantConfigRequest,
): Promise<TenantConfig> {
  return request<TenantConfig>({
    url: `/tenants/${tenantId}/configs`,
    method: 'PUT',
    data: payload,
  })
}

export function generateTenantBill(
  tenantId: ApiId,
  payload: TenantBillGenerateRequest,
): Promise<TenantBill> {
  return request<TenantBill>({ url: `/tenants/${tenantId}/bills`, method: 'POST', data: payload })
}

export function tenantBills(tenantId: ApiId): Promise<TenantBill[]> {
  return request<TenantBill[]>({ url: `/tenants/${tenantId}/bills` })
}

export function requestTenantExport(
  tenantId: ApiId,
  payload: TenantExportRequest,
): Promise<TenantExportJob> {
  return request<TenantExportJob>({
    url: `/tenants/${tenantId}/exports`,
    method: 'POST',
    data: payload,
  })
}

export function tenantExports(tenantId: ApiId): Promise<TenantExportJob[]> {
  return request<TenantExportJob[]>({ url: `/tenants/${tenantId}/exports` })
}

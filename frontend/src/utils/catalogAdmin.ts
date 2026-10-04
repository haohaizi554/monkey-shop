import { parsePositiveApiId, type ApiId } from '@/api/ids'
import type {
  CatalogSpu,
  CatalogSpuWriteRequest,
  ProductStatus,
} from '@/types'

export interface CatalogAdminForm {
  id: ApiId | null
  categoryId: string
  shopId: string
  name: string
  title: string
  originalPrice: string
  memberPrice: string
  strikePrice: string
  regionPricesJson: string
  attributesJson: string
  specificationsJson: string
  detailJsonLd: string
  supplierPrivateRemark: string
  imageUrl: string
}

export function emptyCatalogAdminForm(): CatalogAdminForm {
  return {
    id: null,
    categoryId: '',
    shopId: '',
    name: '',
    title: '',
    originalPrice: '',
    memberPrice: '',
    strikePrice: '',
    regionPricesJson: '{}',
    attributesJson: '{}',
    specificationsJson: '{"variant":["Default"]}',
    detailJsonLd: '',
    supplierPrivateRemark: '',
    imageUrl: '',
  }
}

export function catalogToAdminForm(product: CatalogSpu): CatalogAdminForm {
  const form = emptyCatalogAdminForm()
  form.id = product.id
  form.categoryId = String(product.categoryId)
  form.shopId = String(product.shopId)
  form.name = product.name
  form.title = product.title
  form.originalPrice = String(product.originalPrice)
  form.memberPrice = product.memberPrice == null ? '' : String(product.memberPrice)
  form.strikePrice = product.strikePrice == null ? '' : String(product.strikePrice)
  form.regionPricesJson = JSON.stringify(product.regionPrices ?? {}, null, 2)
  form.attributesJson = JSON.stringify(product.attributes ?? {}, null, 2)
  form.specificationsJson = JSON.stringify(specificationsFromSkus(product), null, 2)
  form.detailJsonLd = product.detailJsonLd ?? ''
  form.imageUrl = product.imageUrl ?? ''
  return form
}

export function catalogAdminFormPayload(form: CatalogAdminForm): CatalogSpuWriteRequest {
  const categoryId = parsePositiveApiId(form.categoryId)
  const shopId = parsePositiveApiId(form.shopId)
  if (categoryId === undefined || shopId === undefined) {
    throw new Error('Category and shop IDs must be positive integers')
  }
  const regionPrices = parseJsonRecord(form.regionPricesJson, 'Regional prices') as Record<
    string,
    string | number
  >
  const attributes = parseJsonRecord(form.attributesJson, 'Attributes')
  const specifications = parseSpecifications(form.specificationsJson)
  return {
    categoryId,
    shopId,
    name: form.name.trim(),
    title: form.title.trim(),
    originalPrice: form.originalPrice.trim(),
    memberPrice: nullableText(form.memberPrice),
    strikePrice: nullableText(form.strikePrice),
    regionPrices,
    attributes,
    detailJsonLd: nullableText(form.detailJsonLd),
    supplierPrivateRemark: nullableText(form.supplierPrivateRemark),
    imageUrl: nullableText(form.imageUrl),
    specifications,
  }
}

export function allowedCatalogStatusTargets(status: ProductStatus): ProductStatus[] {
  switch (status) {
    case 'DRAFT':
      return ['PENDING_REVIEW', 'RECYCLED']
    case 'PENDING_REVIEW':
      return ['APPROVED', 'DRAFT']
    case 'APPROVED':
      return ['LISTED', 'RECYCLED']
    case 'LISTED':
      return ['UNLISTED']
    case 'UNLISTED':
      return ['LISTED', 'RECYCLED']
    case 'RECYCLED':
      return []
  }
}

function specificationsFromSkus(product: CatalogSpu): Record<string, string[]> {
  const dimensions: Record<string, string[]> = {}
  for (const sku of product.skus ?? []) {
    for (const [name, value] of Object.entries(sku.specification ?? {})) {
      const values = dimensions[name] ?? []
      if (!values.includes(value)) values.push(value)
      dimensions[name] = values
    }
  }
  return Object.keys(dimensions).length > 0 ? dimensions : { variant: ['Default'] }
}

function parseSpecifications(raw: string): Array<{ name: string; values: string[] }> {
  const record = parseJsonRecord(raw, 'Specifications')
  const dimensions = Object.entries(record).map(([name, values]) => {
    if (!name.trim() || !Array.isArray(values)) {
      throw new Error('Specifications must map each dimension name to a non-empty array')
    }
    const normalizedValues = values
      .map((value) => String(value).trim())
      .filter((value) => value.length > 0)
    if (normalizedValues.length === 0) {
      throw new Error('Specifications must include at least one value per dimension')
    }
    return { name: name.trim(), values: normalizedValues }
  })
  if (dimensions.length === 0) throw new Error('At least one SKU specification is required')
  return dimensions
}

function parseJsonRecord(raw: string, label: string): Record<string, unknown> {
  let value: unknown
  try {
    value = JSON.parse(raw || '{}')
  } catch {
    throw new Error(`${label} must be valid JSON`)
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label} must be a JSON object`)
  }
  return value as Record<string, unknown>
}

function nullableText(value: string): string | null {
  const normalized = value.trim()
  return normalized.length > 0 ? normalized : null
}

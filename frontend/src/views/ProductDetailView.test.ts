import type { AxiosRequestConfig } from 'axios'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { i18n } from '@/locales'
import ProductDetailView from './ProductDetailView.vue'

const requestMock = vi.hoisted(() => vi.fn())
const checkoutMock = vi.hoisted(() => ({ openCheckout: vi.fn() }))

vi.mock('@/api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/http')>()
  return { ...actual, request: requestMock }
})

vi.mock('@/api/cart', () => ({
  addCartItem: vi.fn(),
}))

vi.mock('@/api/inventory', () => ({
  inventoryStocks: vi.fn(async (skuId: number) => [
    {
      skuId,
      warehouseId: 1,
      availableQuantity: 6,
      lockedQuantity: 0,
      deductedQuantity: 0,
      inTransitQuantity: 0,
      safetyStock: 2,
      totalQuantity: 6,
      belowSafetyStock: false,
    },
  ]),
}))

vi.mock('@/api/marketing', () => ({
  quoteMarketingPrice: vi.fn(async () => ({
    originalAmount: '139.00',
    discountAmount: '0.00',
    payableAmount: '139.00',
    appliedRules: [],
  })),
}))

vi.mock('@/api/membership', () => ({
  addCollection: vi.fn(),
  membershipDashboard: vi.fn(async () => ({ collections: [] })),
  recordBrowse: vi.fn(async () => undefined),
  removeCollection: vi.fn(),
}))

vi.mock('@/composables/useCheckout', async () => {
  const { reactive, ref } = await import('vue')
  return {
    useCheckout: () => ({
      openingCheckoutId: ref<number | null>(null),
      submittingOrder: ref(false),
      savingAddress: ref(false),
      checkoutOpen: ref(false),
      addresses: ref([]),
      selectedMonkey: ref(null),
      selectedAddressId: ref<number | null>(null),
      newAddress: reactive({ receiverName: '', phone: '', detailAddress: '' }),
      openCheckout: checkoutMock.openCheckout,
      saveAddress: vi.fn(),
      submitOrder: vi.fn(),
    }),
  }
})

vi.mock('@/composables/useNotify', () => ({
  useNotify: () => ({
    error: vi.fn(),
    success: vi.fn(),
    notify: vi.fn(),
  }),
}))

vi.mock('@/seo/useJsonLd', () => ({
  useJsonLd: vi.fn(),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isLoggedIn: true }),
}))

vi.mock('@/TrackingSdk', () => ({
  trackEvent: vi.fn(),
}))

interface MountedView {
  app: App
  host: HTMLElement
  router: Router
}

const mounted: MountedView[] = []

const canonicalSpuId = '338329504114688001'
const canonicalSkuId = '338329504114688101'
const canonicalSpu = {
  id: canonicalSpuId,
  categoryId: 7,
  name: 'Canonical Capuchin',
  title: 'Rainforest Explorer',
  status: 'LISTED',
  originalPrice: '199.00',
  memberPrice: '149.00',
  strikePrice: '219.00',
  regionPrices: {},
  attributes: { description: 'Canonical product description', shopId: 9 },
  imageUrl: '/images/default_product.jpg',
  skus: [
    {
      id: canonicalSkuId,
      spuId: canonicalSpuId,
      skuCode: 'CAP-GOLD',
      specification: { Color: 'Gold' },
      originalPrice: '159.00',
      memberPrice: '149.00',
      strikePrice: '179.00',
      regionPrices: {},
      active: true,
    },
  ],
}

async function mountProductDetail(): Promise<MountedView> {
  const host = document.createElement('div')
  document.body.append(host)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/shop/:productId', component: ProductDetailView },
      { path: '/shop', component: { template: '<div>shop</div>' } },
      { path: '/login', component: { template: '<div>login</div>' } },
    ],
  })
  await router.push(`/shop/${canonicalSpuId}`)
  await router.isReady()

  const app = createApp(ProductDetailView)
  app.use(router).use(i18n).use(ElementPlus)
  app.mount(host)
  const view = { app, host, router }
  mounted.push(view)

  await vi.waitFor(() => expect(host.textContent).toContain(canonicalSpu.name))
  await nextTick()
  return view
}

beforeEach(() => {
  i18n.global.locale.value = 'en'
  requestMock.mockImplementation(async (config: AxiosRequestConfig) => {
    if (config.url === '/monkeys') {
      const page = Number((config.params as { page?: number } | undefined)?.page ?? 0)
      return {
        content: [{ id: page + 1, name: `Legacy product ${page + 1}` }],
        page,
        size: 100,
        totalElements: 3,
        totalPages: 3,
        first: page === 0,
        last: page === 2,
      }
    }
    if (config.url === `/catalog/spus/${canonicalSpuId}`) {
      return canonicalSpu
    }
    if (config.url === `/catalog/spus/${canonicalSpuId}/price`) {
      return {
        spuId: canonicalSpuId,
        salePrice: '139.00',
        strikePrice: '179.00',
        strategy: 'MEMBER',
      }
    }
    throw new Error(`Unexpected request: ${config.url}`)
  })
})

afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  vi.clearAllMocks()
})

describe('ProductDetailView canonical catalog loading', () => {
  it('loads one SPU without paging through monkeys and keeps its SKU checkout available', async () => {
    const { host } = await mountProductDetail()

    await vi.waitFor(() =>
      expect(host.querySelector('[data-testid="commerce-price"]')?.textContent).toContain('139.00'),
    )
    await vi.waitFor(() =>
      expect(host.querySelector('[data-testid="inventory-summary"]')?.textContent).toContain('6'),
    )
    expect(host.textContent).toContain('Canonical product description')
    expect(host.textContent).toContain('Color: Gold')

    const requests = requestMock.mock.calls.map(([config]) => config as AxiosRequestConfig)
    expect(requests.filter((config) => config.url === '/monkeys')).toHaveLength(0)

    const spuRequest = requests.find((config) => config.url === `/catalog/spus/${canonicalSpuId}`)
    const priceRequest = requests.find(
      (config) => config.url === `/catalog/spus/${canonicalSpuId}/price`,
    )
    expect(spuRequest?.signal).toBeInstanceOf(AbortSignal)
    expect(priceRequest?.signal).toBeInstanceOf(AbortSignal)

    const buyButton = host.querySelector<HTMLButtonElement>('.purchase-action')
    expect(buyButton).not.toBeNull()
    expect(buyButton?.disabled).toBe(false)
    buyButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()

    expect(checkoutMock.openCheckout).toHaveBeenCalledWith(
      expect.objectContaining({
        id: canonicalSpuId,
        name: canonicalSpu.name,
        selectedSkuId: canonicalSkuId,
      }),
      { skuId: canonicalSkuId, shopId: 9, quantity: 1 },
    )
  })
})

import type { AxiosRequestConfig } from 'axios'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App, reactive, ref } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { i18n } from '@/locales'
import ShopView from './ShopView.vue'

const requestMock = vi.hoisted(() => vi.fn())
const checkoutMock = vi.hoisted(() => ({ openCheckout: vi.fn() }))

vi.mock('@/api/http', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/http')>()
  return {
    ...actual,
    request: requestMock,
  }
})

vi.mock('@/composables/useCheckout', () => ({
  useCheckout: () => ({
    openingCheckoutId: ref(null),
    submittingOrder: ref(false),
    savingAddress: ref(false),
    loadingAddresses: ref(false),
    checkoutOpen: ref(false),
    addresses: ref([]),
    addressPageNumber: ref(0),
    addressPageSize: 6,
    addressTotal: ref(0),
    selectedMonkey: ref(null),
    selectedAddressId: ref(null),
    newAddress: reactive({ receiverName: '', phone: '', detailAddress: '' }),
    openCheckout: checkoutMock.openCheckout,
    changeAddressPage: vi.fn(),
    saveAddress: vi.fn(),
    submitOrder: vi.fn(),
  }),
}))

vi.mock('@/composables/useNotify', () => ({
  useNotify: () => ({
    error: vi.fn(),
    notify: vi.fn(),
  }),
}))

vi.mock('@/seo/useJsonLd', () => ({
  useJsonLd: vi.fn(),
}))

interface MountedView {
  app: App
  host: HTMLElement
  router: Router
}

const mounted: MountedView[] = []
const canonicalSpuId = '338329504114688001'
let mockedStock = 3

beforeEach(() => {
  i18n.global.locale.value = 'en'
  requestMock.mockImplementation(async (config: AxiosRequestConfig) => {
    if (config.url === '/search/products') {
      return {
        content: [
          {
            productId: canonicalSpuId,
            categoryId: '338329504114688007',
            name: 'Canonical catalog product',
            title: 'Listed catalog result',
            imageUrl: '/images/catalog-result.jpg',
            originalPrice: '199.00',
            memberPrice: '149.00',
            stock: mockedStock,
            attributes: {},
            score: 1,
          },
        ],
        page: 0,
        size: 12,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      }
    }
    if (config.url === '/catalog/categories/tree') {
      return []
    }
    if (config.url === `/catalog/spus/${canonicalSpuId}`) {
      return {
        id: canonicalSpuId,
        categoryId: '338329504114688007',
        shopId: '338329504114688201',
        name: 'Canonical catalog product',
        title: 'Listed catalog result',
        status: 'LISTED',
        originalPrice: '199.00',
        memberPrice: '149.00',
        strikePrice: '219.00',
        regionPrices: {},
        attributes: {},
        imageUrl: '/images/catalog-result.jpg',
        skus: [
          {
            id: '338329504114688101',
            spuId: canonicalSpuId,
            skuCode: 'CATALOG-DEFAULT',
            specification: { Color: 'Gold' },
            originalPrice: '199.00',
            memberPrice: '149.00',
            strikePrice: '219.00',
            regionPrices: {},
            active: true,
          },
        ],
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
  mockedStock = 3
  vi.clearAllMocks()
})

async function mountShop(): Promise<MountedView> {
  const host = document.createElement('div')
  document.body.append(host)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/shop', component: ShopView },
      { path: '/shop/:productId', component: { template: '<div>detail</div>' } },
    ],
  })
  await router.push('/shop')
  await router.isReady()

  const app = createApp(ShopView)
  app.use(router).use(i18n).use(ElementPlus)
  app.mount(host)
  const view = { app, host, router }
  mounted.push(view)
  await vi.waitFor(() => expect(host.textContent).toContain('Canonical catalog product'))
  await nextTick()
  return view
}

describe('ShopView catalog identity', () => {
  it('loads listed catalog results and routes their exact SPU id to detail', async () => {
    const { host, router } = await mountShop()

    const requests = requestMock.mock.calls.map(([config]) => config as AxiosRequestConfig)
    expect(requests.some((config) => config.url === '/monkeys')).toBe(false)
    expect(requests.find((config) => config.url === '/search/products')).toMatchObject({
      url: '/search/products',
      params: { page: 0, size: 12, sort: 'NEWEST' },
    })

    host.querySelector<HTMLButtonElement>('.product-card__title')?.click()
    await vi.waitFor(() =>
      expect(router.currentRoute.value.fullPath).toBe(`/shop/${canonicalSpuId}`),
    )
  })

  it('opens catalog checkout with the canonical SKU and shop ids', async () => {
    const { host } = await mountShop()

    host.querySelector<HTMLButtonElement>('.product-card__primary')?.click()
    await vi.waitFor(() => expect(checkoutMock.openCheckout).toHaveBeenCalledOnce())

    expect(checkoutMock.openCheckout).toHaveBeenCalledWith(
      expect.objectContaining({ id: canonicalSpuId, name: 'Canonical catalog product' }),
      {
        skuId: '338329504114688101',
        shopId: '338329504114688201',
        quantity: 1,
      },
    )
  })

  it('disables purchase for a catalog result whose aggregate stock is zero', async () => {
    mockedStock = 0
    const { host } = await mountShop()

    const buyButton = host.querySelector<HTMLButtonElement>('.product-card__primary')
    expect(buyButton).not.toBeNull()
    expect(buyButton?.disabled).toBe(true)
    buyButton?.click()

    expect(checkoutMock.openCheckout).not.toHaveBeenCalled()
  })
})

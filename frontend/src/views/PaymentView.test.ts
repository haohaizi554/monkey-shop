import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import * as paymentsApi from '@/api/payments'
import { ApiError } from '@/api/http'
import { i18n } from '@/locales'
import type { PaymentResponse } from '@/types'
import { navigateToPaymentProvider } from '@/utils/paymentRedirect'
import PaymentView from './PaymentView.vue'

const notify = vi.hoisted(() => ({
  success: vi.fn(),
  info: vi.fn(),
  confirm: vi.fn(),
}))

const navigateMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/payments', () => ({
  createPayment: vi.fn(),
  paymentForOrder: vi.fn(),
  refundPayment: vi.fn(),
}))

vi.mock('@/composables/useNotify', () => ({
  useNotify: () => notify,
}))

vi.mock('@/utils/paymentRedirect', async () => {
  const actual =
    await vi.importActual<typeof import('@/utils/paymentRedirect')>('@/utils/paymentRedirect')
  return {
    ...actual,
    navigateToPaymentProvider: navigateMock,
  }
})

interface MountedView {
  app: App
  host: HTMLElement
  router: Router
}

const mounted: MountedView[] = []

function payment(overrides: Partial<PaymentResponse> = {}): PaymentResponse {
  return {
    id: 1,
    paymentNo: 'PAY-42',
    orderId: 42,
    userId: 7,
    method: 'WECHAT',
    amount: '99.00',
    paidAmount: '0.00',
    refundedAmount: '0.00',
    status: 'PENDING',
    createTime: '2026-07-21T09:00:00+08:00',
    ...overrides,
  }
}

async function mountPayment(): Promise<MountedView> {
  const host = document.createElement('div')
  document.body.append(host)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/payment/:orderId', component: PaymentView }],
  })
  await router.push('/payment/42')
  await router.isReady()

  const app = createApp(PaymentView)
  app.use(router).use(i18n).use(ElementPlus)
  app.mount(host)
  const view = { app, host, router }
  mounted.push(view)
  await vi.waitFor(() => expect(paymentsApi.paymentForOrder).toHaveBeenCalledWith(42))
  await nextTick()
  return view
}

async function submitPayment(host: HTMLElement) {
  const button = host.querySelector<HTMLButtonElement>('.create-task button[type="submit"]')
  expect(button).not.toBeNull()
  await vi.waitFor(() => expect(button?.disabled).toBe(false))
  button?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await nextTick()
}

beforeEach(() => {
  i18n.global.locale.value = 'zh'
  vi.mocked(paymentsApi.paymentForOrder).mockRejectedValue(
    new ApiError('not found', 404, 'trace-payment'),
  )
  vi.mocked(paymentsApi.createPayment).mockResolvedValue(payment())
  vi.mocked(paymentsApi.refundPayment).mockResolvedValue({
    ledgerId: 1,
    paymentNo: 'PAY-42',
    amount: '1.00',
    refundedAmount: '1.00',
    paymentStatus: 'PARTIALLY_REFUNDED',
    ledgerStatus: 'SUCCESS',
    createTime: '2026-07-21T09:05:00+08:00',
  })
})

afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  vi.clearAllMocks()
})

describe('PaymentView provider redirect', () => {
  it('automatically opens a safe provider URL after creating a pending payment', async () => {
    vi.mocked(paymentsApi.createPayment).mockResolvedValue(
      payment({ paymentUrl: 'https://pay.example.test/checkout/PAY-42' }),
    )
    const { host } = await mountPayment()

    await submitPayment(host)

    await vi.waitFor(() =>
      expect(navigateToPaymentProvider).toHaveBeenCalledWith(
        'https://pay.example.test/checkout/PAY-42',
      ),
    )
    expect(host.querySelector('[data-testid="payment-provider-continue"]')).not.toBeNull()
  })

  it('blocks a dangerous provider URL and explains that redirect is unavailable', async () => {
    vi.mocked(paymentsApi.createPayment).mockResolvedValue(
      payment({ paymentUrl: 'javascript:alert(document.domain)' }),
    )
    const { host } = await mountPayment()

    await submitPayment(host)
    await vi.waitFor(() => expect(paymentsApi.createPayment).toHaveBeenCalledOnce())

    expect(navigateToPaymentProvider).not.toHaveBeenCalled()
    expect(host.querySelector('[data-testid="payment-provider-continue"]')).toBeNull()
    await vi.waitFor(() =>
      expect(host.querySelector('[data-testid="payment-redirect-error"]')).not.toBeNull(),
    )
  })

  it('offers a safe resume action without auto-redirecting an existing payment', async () => {
    vi.mocked(paymentsApi.paymentForOrder).mockResolvedValue(
      payment({ paymentUrl: '/provider/checkout/PAY-42' }),
    )

    const { host } = await mountPayment()

    expect(navigateToPaymentProvider).not.toHaveBeenCalled()
    expect(host.querySelector('[data-testid="payment-provider-continue"]')).not.toBeNull()
  })
})

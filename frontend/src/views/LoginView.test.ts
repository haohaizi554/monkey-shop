import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import * as authApi from '@/api/auth'
import { ApiError } from '@/api/http'
import { i18n } from '@/locales'
import LoginView from './LoginView.vue'

vi.mock('@/api/auth', () => ({
  captchaUrl: vi.fn(() => '/api/v1/auth/captcha?test=1'),
  captchaConfig: vi.fn(),
  passwordPolicy: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  register: vi.fn(),
  requestPasswordReset: vi.fn(),
  resetPassword: vi.fn(),
}))

vi.mock('@/api/user', () => ({
  me: vi.fn(async () => ({ isLogin: false })),
}))

interface MountedView {
  app: App
  host: HTMLElement
  router: Router
}

const mounted: MountedView[] = []

async function mountLogin(): Promise<MountedView> {
  const host = document.createElement('div')
  document.body.append(host)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginView },
      { path: '/shop', component: { template: '<div>shop</div>' } },
      { path: '/admin', component: { template: '<div>admin</div>' } },
      { path: '/profile', component: { template: '<div>profile</div>' } },
    ],
  })
  await router.push('/login')
  await router.isReady()

  const app = createApp(LoginView)
  app.use(createPinia()).use(router).use(i18n).use(ElementPlus)
  app.mount(host)
  const view = { app, host, router }
  mounted.push(view)
  await vi.waitFor(() => expect(authApi.passwordPolicy).toHaveBeenCalledOnce())
  return view
}

async function click(host: HTMLElement, selector: string) {
  const button = host.querySelector<HTMLElement>(selector)
  expect(button, selector).not.toBeNull()
  button?.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  await nextTick()
}

async function fill(host: HTMLElement, selector: string, value: string) {
  const target = host.querySelector<HTMLElement>(selector)
  const input =
    target instanceof HTMLInputElement ? target : target?.querySelector<HTMLInputElement>('input')
  expect(input, selector).not.toBeNull()
  if (!input) return
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}

async function submitValidRegistration(host: HTMLElement, captcha = '1234') {
  await fill(host, '[data-testid="register-username"]', 'member')
  await fill(host, '[data-testid="register-password"]', 'ValidPass!1')
  await click(host, '[data-testid="register-next"]')
  await vi.waitFor(() =>
    expect(host.querySelector('[data-testid="register-contact-step"]')).not.toBeNull(),
  )
  await fill(host, '[data-testid="register-phone"]', '13800138000')
  await fill(host, '[data-testid="register-captcha"]', captcha)
  await click(host, '[data-testid="register-submit"]')
}

beforeEach(() => {
  i18n.global.locale.value = 'zh'
  vi.mocked(authApi.captchaConfig).mockResolvedValue({ provider: 'local', siteKey: '' })
  vi.mocked(authApi.passwordPolicy).mockResolvedValue({
    minLength: 10,
    requireUppercase: true,
    requireLowercase: true,
    requireDigit: true,
    requireSpecial: true,
    forbidWhitespace: true,
  })
  vi.mocked(authApi.login).mockResolvedValue({ role: 'USER', passwordChangeRequired: false })
  vi.mocked(authApi.register).mockResolvedValue(undefined)
  vi.mocked(authApi.requestPasswordReset).mockResolvedValue(undefined)
  vi.mocked(authApi.resetPassword).mockResolvedValue(undefined)
})

afterEach(() => {
  for (const { app, host } of mounted.splice(0)) {
    app.unmount()
    host.remove()
  }
  vi.clearAllMocks()
})

describe('LoginView registration journey', () => {
  it('keeps account and contact registration steps exclusive and blocks weak passwords', async () => {
    const { host } = await mountLogin()

    await click(host, '[data-testid="register-tab"]')
    expect(host.querySelector('[data-testid="register-account-step"]')).not.toBeNull()
    expect(host.querySelector('[data-testid="register-contact-step"]')).toBeNull()

    await fill(host, '[data-testid="register-username"]', 'member')
    await fill(host, '[data-testid="register-password"]', 'weakpass')
    await click(host, '[data-testid="register-next"]')

    expect(host.querySelector('[data-testid="register-account-step"]')).not.toBeNull()
    expect(authApi.register).not.toHaveBeenCalled()

    await fill(host, '[data-testid="register-password"]', 'ValidPass!1')
    await click(host, '[data-testid="register-next"]')
    await vi.waitFor(() =>
      expect(host.querySelector('[data-testid="register-contact-step"]')).not.toBeNull(),
    )
    expect(host.querySelector('[data-testid="register-account-step"]')).toBeNull()
  })

  it('renders trusted 422 field violations beside the matching registration field', async () => {
    vi.mocked(authApi.register).mockRejectedValue(
      new ApiError('validation failed', 422, 'trace-register', 'VALIDATION_FAILED', {
        fieldErrors: [{ field: 'username', code: 'Unique', message: '用户名已存在' }],
      }),
    )
    const { host } = await mountLogin()

    await click(host, '[data-testid="register-tab"]')
    await fill(host, '[data-testid="register-username"]', 'member')
    await fill(host, '[data-testid="register-password"]', 'ValidPass!1')
    await click(host, '[data-testid="register-next"]')
    await vi.waitFor(() =>
      expect(host.querySelector('[data-testid="register-contact-step"]')).not.toBeNull(),
    )
    await fill(host, '[data-testid="register-phone"]', '13800138000')
    await fill(host, '[data-testid="register-captcha"]', '1234')
    await click(host, '[data-testid="register-submit"]')

    await vi.waitFor(() =>
      expect(host.querySelector('[data-field-error="username"]')?.textContent).toContain(
        '用户名已存在',
      ),
    )
    expect(host.querySelector('[data-testid="register-account-step"]')).not.toBeNull()
  })
})

describe('LoginView retry state', () => {
  it('disables only submission while a 429 retry countdown is active', async () => {
    vi.mocked(authApi.login).mockRejectedValue(
      new ApiError('Too many requests', 429, 'trace-login', 'RATE_LIMIT', {
        retryAfterSeconds: 10,
      }),
    )
    const { host } = await mountLogin()

    await fill(host, '[data-testid="login-username"]', 'member')
    await fill(host, '[data-testid="login-password"]', 'bad-password')
    await click(host, '[data-testid="login-submit"]')

    await vi.waitFor(() =>
      expect(host.querySelector('[data-testid="retry-countdown"]')?.textContent).toContain('10'),
    )
    expect(host.querySelector<HTMLButtonElement>('[data-testid="login-submit"]')?.disabled).toBe(
      true,
    )
    expect(host.querySelector<HTMLInputElement>('[data-testid="login-username"]')?.disabled).toBe(
      false,
    )
    expect(host.textContent).not.toContain('Too many requests')
  })

  it('keeps a login 429 out of registration and handles captcha 422 without a countdown', async () => {
    vi.mocked(authApi.login).mockRejectedValue(
      new ApiError('Too many requests', 429, 'trace-login', 'RATE_LIMIT', {
        retryAfterSeconds: 10,
      }),
    )
    vi.mocked(authApi.register).mockRejectedValue(
      new ApiError('captcha incorrect', 422, 'trace-register', 'VALIDATION_FAILED'),
    )
    const { host } = await mountLogin()

    await fill(host, '[data-testid="login-username"]', 'member')
    await fill(host, '[data-testid="login-password"]', 'bad-password')
    await click(host, '[data-testid="login-submit"]')
    await vi.waitFor(() =>
      expect(host.querySelector('[data-testid="retry-countdown"]')?.textContent).toContain('10'),
    )

    vi.mocked(authApi.captchaUrl).mockClear()
    await click(host, '[data-testid="register-tab"]')
    expect(host.querySelector('[data-testid="retry-countdown"]')).toBeNull()
    expect(host.querySelector<HTMLButtonElement>('[data-testid="register-next"]')?.disabled).toBe(
      false,
    )

    await submitValidRegistration(host, 'wrong-code')

    await vi.waitFor(() => expect(authApi.register).toHaveBeenCalledOnce())
    await vi.waitFor(() => {
      const captchaField = host.querySelector<HTMLElement>('[data-testid="register-captcha"]')
      const captchaInput =
        captchaField instanceof HTMLInputElement
          ? captchaField
          : captchaField?.querySelector<HTMLInputElement>('input')
      expect(captchaInput?.value).toBe('')
    })
    expect(authApi.captchaUrl).toHaveBeenCalledOnce()
    expect(authApi.captchaUrl).toHaveBeenCalledWith('auth')
    expect(host.querySelector('[data-testid="retry-countdown"]')).toBeNull()
    expect(host.textContent).toContain(i18n.global.t('auth.captchaIncorrect'))
    expect(host.textContent).not.toContain('captcha incorrect')

    await click(host, '[data-testid="login-tab"]')
    expect(host.querySelector('[data-testid="retry-countdown"]')).not.toBeNull()
    expect(host.textContent).not.toContain(i18n.global.t('auth.captchaIncorrect'))
    expect(host.textContent).not.toContain('Too many requests')

    await click(host, '[data-testid="register-tab"]')
    expect(host.textContent).toContain(i18n.global.t('auth.captchaIncorrect'))
  })

  it('disables registration only when registration receives a 429', async () => {
    vi.mocked(authApi.register).mockRejectedValue(
      new ApiError('Operation is not permitted', 429, 'trace-register', 'RATE_LIMIT', {
        retryAfterSeconds: 10,
      }),
    )
    const { host } = await mountLogin()

    await click(host, '[data-testid="register-tab"]')
    await submitValidRegistration(host)

    await vi.waitFor(() =>
      expect(host.querySelector('[data-testid="retry-countdown"]')?.textContent).toContain('10'),
    )
    expect(host.querySelector<HTMLButtonElement>('[data-testid="register-submit"]')?.disabled).toBe(
      true,
    )
    expect(host.textContent).not.toContain('Operation is not permitted')

    await click(host, '[data-testid="login-tab"]')
    expect(host.querySelector('[data-testid="retry-countdown"]')).toBeNull()
    expect(host.querySelector<HTMLButtonElement>('[data-testid="login-submit"]')?.disabled).toBe(
      false,
    )

    await click(host, '[data-testid="register-tab"]')
    expect(host.querySelector('[data-testid="retry-countdown"]')).not.toBeNull()
    expect(host.querySelector<HTMLButtonElement>('[data-testid="register-submit"]')?.disabled).toBe(
      true,
    )
  })

  it('refreshes a one-time local captcha after a non-validation registration failure', async () => {
    vi.mocked(authApi.register).mockRejectedValue(
      new ApiError('Service unavailable', 503, 'trace-register', 'SERVICE_UNAVAILABLE'),
    )
    const { host } = await mountLogin()

    await click(host, '[data-testid="register-tab"]')
    vi.mocked(authApi.captchaUrl).mockClear()
    await submitValidRegistration(host)

    await vi.waitFor(() => expect(authApi.register).toHaveBeenCalledOnce())
    await vi.waitFor(() => expect(authApi.captchaUrl).toHaveBeenCalledWith('auth'))
    const captchaField = host.querySelector<HTMLElement>('[data-testid="register-captcha"]')
    const captchaInput =
      captchaField instanceof HTMLInputElement
        ? captchaField
        : captchaField?.querySelector<HTMLInputElement>('input')
    expect(captchaInput?.value).toBe('')
    expect(host.querySelector('[data-testid="retry-countdown"]')).toBeNull()
  })
})

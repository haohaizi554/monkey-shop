import { request } from './http'
import type {
  LoginRequest,
  LoginResponse,
  CaptchaConfig,
  PasswordPolicy,
  PasswordResetChallenge,
  PasswordResetRequest,
  RegisterRequest,
} from '@/types'

export function captchaUrl(scope: 'auth' | 'user' = 'auth'): string {
  const captchaEndpoints = {
    auth: '/api/v1/auth/captcha',
    user: '/api/v1/users/captcha',
  }
  return `${captchaEndpoints[scope]}?t=${Date.now()}`
}

export async function captchaConfig(): Promise<CaptchaConfig> {
  return request<CaptchaConfig>({
    url: '/auth/captcha/config',
    method: 'GET',
  })
}

export async function passwordPolicy(): Promise<PasswordPolicy> {
  return request<PasswordPolicy>({
    url: '/auth/password-policy',
    method: 'GET',
  })
}

export async function login(payload: LoginRequest): Promise<LoginResponse> {
  return request<LoginResponse>({
    url: '/auth/login',
    method: 'POST',
    data: payload,
  })
}

export async function logout(): Promise<void> {
  await request<void>({
    url: '/users/logout',
    method: 'POST',
  })
}

export async function register(payload: RegisterRequest): Promise<void> {
  const form = new FormData()
  form.set('username', payload.username)
  form.set('password', payload.password)
  form.set('phone', payload.phone)
  form.set('captcha', payload.captcha)
  if (payload.email) {
    form.set('email', payload.email)
  }
  if (payload.avatarFile) {
    form.set('avatarFile', payload.avatarFile)
  }
  await request<void>({
    url: '/auth/register',
    method: 'POST',
    data: form,
  })
}

export async function requestPasswordReset(payload: PasswordResetChallenge): Promise<void> {
  await request<void>({
    url: '/auth/reset-password/request',
    method: 'POST',
    data: payload,
  })
}

export async function resetPassword(payload: PasswordResetRequest): Promise<void> {
  await request<void>({
    url: '/auth/reset-password',
    method: 'POST',
    data: payload,
  })
}

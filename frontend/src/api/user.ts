import { request } from './http'
import type { PageEnvelope } from './page'
import type { Address, AddressRequest, AvatarUpdateRequest, UserProfile } from '@/types'

export function me(): Promise<UserProfile> {
  return request<UserProfile>({ url: '/users/me' })
}

export function profile(signal?: AbortSignal): Promise<UserProfile> {
  return request<UserProfile>({ url: '/users/profile', signal })
}

export interface AddressPageQuery {
  page: number
  size: number
  sort?: string
  signal?: AbortSignal
}

export function addressPage(query: AddressPageQuery): Promise<PageEnvelope<Address>> {
  const { signal, ...params } = query
  return request<PageEnvelope<Address>>({ url: '/addresses', params, signal })
}

export function addAddress(payload: AddressRequest): Promise<Address> {
  return request<Address>({ url: '/addresses', method: 'POST', data: payload })
}

export function updateAddress(id: number, payload: AddressRequest): Promise<Address> {
  return request<Address>({ url: `/addresses/${id}`, method: 'PUT', data: payload })
}

export function setDefaultAddress(id: number): Promise<Address> {
  return request<Address>({ url: `/addresses/set-default/${id}`, method: 'POST' })
}

export async function deleteAddress(id: number): Promise<void> {
  await request<void>({ url: `/addresses/${id}`, method: 'DELETE' })
}

export async function updateAvatar(avatarPath: string): Promise<void> {
  const payload: AvatarUpdateRequest = { avatarPath }
  await request<void>({ url: '/users/update-avatar', method: 'POST', data: payload })
}

export async function updatePassword(payload: {
  oldPassword: string
  phone: string
  newPassword: string
  captcha: string
}): Promise<void> {
  await request<void>({ url: '/users/update-password', method: 'POST', data: payload })
}

export async function forgetMe(): Promise<void> {
  await request<void>({ url: '/users/forget-me', method: 'POST' })
}

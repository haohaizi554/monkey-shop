import { request } from './http'
import type { Monkey, MonkeyRequest, UploadResponse } from '@/types'

export function listMonkeys(): Promise<Monkey[]> {
  return request<Monkey[]>({ url: '/monkeys' })
}

export function addMonkey(payload: MonkeyRequest): Promise<Monkey> {
  return request<Monkey>({ url: '/monkeys/add', method: 'POST', data: payload })
}

export function updateMonkey(payload: MonkeyRequest): Promise<Monkey> {
  return request<Monkey>({ url: '/monkeys/update', method: 'POST', data: payload })
}

export async function deleteMonkey(id: number): Promise<void> {
  await request<void>({ url: `/monkeys/${id}`, method: 'DELETE' })
}

export async function uploadImage(file: File, type: 'avatar' | 'product'): Promise<UploadResponse> {
  const form = new FormData()
  form.set('file', file)
  form.set('type', type)
  return request<UploadResponse>({ url: '/uploads', method: 'POST', data: form })
}

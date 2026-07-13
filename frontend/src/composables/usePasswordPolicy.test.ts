import { describe, expect, it, vi } from 'vitest'
import type { PasswordPolicy } from '@/types'
import { evaluatePassword, usePasswordPolicy } from '@/composables/usePasswordPolicy'

const policy: PasswordPolicy = {
  minLength: 10,
  requireUppercase: true,
  requireLowercase: true,
  requireDigit: true,
  requireSpecial: true,
  forbidWhitespace: true,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

describe('usePasswordPolicy', () => {
  it('evaluates every password rule published by the backend', () => {
    expect(evaluatePassword('Abcdef1!xx', policy)).toEqual({
      minLength: true,
      uppercase: true,
      lowercase: true,
      digit: true,
      special: true,
      noWhitespace: true,
    })

    expect(evaluatePassword('abcdef ghi', policy)).toEqual({
      minLength: true,
      uppercase: false,
      lowercase: true,
      digit: false,
      special: false,
      noWhitespace: false,
    })
  })

  it('treats disabled optional rules as satisfied', () => {
    expect(
      evaluatePassword('abcdefghij', {
        ...policy,
        requireUppercase: false,
        requireDigit: false,
        requireSpecial: false,
      }),
    ).toMatchObject({ uppercase: true, digit: true, special: true })
  })

  it('matches the backend English character classes', () => {
    expect(evaluatePassword('Αbcdef1!xx', policy).uppercase).toBe(false)
    expect(evaluatePassword('Abcdef١!xx', policy).digit).toBe(false)
  })

  it('loads the policy and evaluates against the latest server contract', async () => {
    const loader = vi.fn().mockResolvedValue(policy)
    const passwordPolicy = usePasswordPolicy(loader)

    await expect(passwordPolicy.load()).resolves.toEqual(policy)

    expect(loader).toHaveBeenCalledOnce()
    expect(passwordPolicy.policy.value).toEqual(policy)
    expect(passwordPolicy.isLoading.value).toBe(false)
    expect(passwordPolicy.error.value).toBeNull()
    expect(passwordPolicy.evaluate('Abcdef1!xx')).toMatchObject({ minLength: true, special: true })
  })

  it('keeps the latest policy when concurrent loads finish out of order', async () => {
    const first = deferred<PasswordPolicy>()
    const second = deferred<PasswordPolicy>()
    const responses = [first.promise, second.promise]
    const passwordPolicy = usePasswordPolicy(vi.fn(() => responses.shift()!))
    const firstLoad = passwordPolicy.load()
    const secondLoad = passwordPolicy.load()
    const latestPolicy = { ...policy, minLength: 14 }

    second.resolve(latestPolicy)
    await expect(secondLoad).resolves.toEqual(latestPolicy)
    expect(passwordPolicy.policy.value).toEqual(latestPolicy)
    expect(passwordPolicy.isLoading.value).toBe(true)

    first.resolve(policy)
    await expect(firstLoad).resolves.toEqual(policy)
    expect(passwordPolicy.policy.value).toEqual(latestPolicy)
    expect(passwordPolicy.isLoading.value).toBe(false)
  })
})

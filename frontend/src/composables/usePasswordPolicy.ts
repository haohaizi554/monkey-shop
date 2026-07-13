import { readonly, ref, shallowRef, type Ref } from 'vue'
import { passwordPolicy as fetchPasswordPolicy } from '@/api/auth'
import type { PasswordPolicy } from '@/types'

export interface PasswordEvaluation {
  minLength: boolean
  uppercase: boolean
  lowercase: boolean
  digit: boolean
  special: boolean
  noWhitespace: boolean
}

export type PasswordPolicyLoader = () => Promise<PasswordPolicy>

export interface PasswordPolicyController {
  policy: Readonly<Ref<PasswordPolicy | null>>
  isLoading: Readonly<Ref<boolean>>
  error: Readonly<Ref<unknown | null>>
  load: () => Promise<PasswordPolicy>
  evaluate: (password: string) => PasswordEvaluation | null
}

export function evaluatePassword(password: string, policy: PasswordPolicy): PasswordEvaluation {
  return {
    minLength: password.length >= Math.max(0, policy.minLength),
    uppercase: !policy.requireUppercase || /[A-Z]/.test(password),
    lowercase: !policy.requireLowercase || /[a-z]/.test(password),
    digit: !policy.requireDigit || /[0-9]/.test(password),
    special:
      !policy.requireSpecial ||
      /[\u0021-\u002F\u003A-\u0040\u005B-\u0060\u007B-\u007E]/.test(password),
    noWhitespace: !policy.forbidWhitespace || !/\s/u.test(password),
  }
}

export function usePasswordPolicy(
  loader: PasswordPolicyLoader = fetchPasswordPolicy,
): PasswordPolicyController {
  const policy = ref<PasswordPolicy | null>(null)
  const isLoading = ref(false)
  const error = shallowRef<unknown | null>(null)
  let latestRequestId = 0
  let pendingLoads = 0

  async function load(): Promise<PasswordPolicy> {
    const requestId = ++latestRequestId
    pendingLoads += 1
    isLoading.value = true
    error.value = null
    try {
      const loadedPolicy = await loader()
      if (requestId === latestRequestId) {
        policy.value = loadedPolicy
      }
      return loadedPolicy
    } catch (caught) {
      if (requestId === latestRequestId) {
        error.value = caught
      }
      throw caught
    } finally {
      pendingLoads -= 1
      isLoading.value = pendingLoads > 0
    }
  }

  function evaluate(password: string): PasswordEvaluation | null {
    return policy.value ? evaluatePassword(password, policy.value) : null
  }

  return {
    policy: readonly(policy),
    isLoading: readonly(isLoading),
    error: readonly(error),
    load,
    evaluate,
  }
}

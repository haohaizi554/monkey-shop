import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as authApi from '@/api/auth'
import * as userApi from '@/api/user'
import type { LoginRequest, RegisterRequest, UserProfile } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const router = useRouter()
  const user = ref<UserProfile>({})
  const loaded = ref(false)

  const isLoggedIn = computed(() => user.value.isLogin === true)
  const isAdmin = computed(() => user.value.identity === 'ADMIN')
  const displayName = computed(() => user.value.username || '访客')
  const passwordChangeRequired = computed(() => user.value.passwordChangeRequired === true)

  async function loadCurrentUser(): Promise<void> {
    try {
      user.value = await userApi.me()
    } catch {
      user.value = {}
    } finally {
      loaded.value = true
    }
  }

  async function login(payload: LoginRequest): Promise<void> {
    const result = await authApi.login(payload)
    await loadCurrentUser()
    if (result.passwordChangeRequired) {
      await router.push('/profile')
      return
    }
    await router.push(result.role === 'ADMIN' ? '/admin' : '/shop')
  }

  async function register(payload: RegisterRequest): Promise<void> {
    await authApi.register(payload)
  }

  async function logout(): Promise<void> {
    await authApi.logout()
    user.value = {}
    await router.push('/login')
  }

  function clearLocalSession(): void {
    user.value = {}
    loaded.value = true
  }

  return {
    user,
    loaded,
    isLoggedIn,
    isAdmin,
    displayName,
    passwordChangeRequired,
    loadCurrentUser,
    login,
    register,
    logout,
    clearLocalSession,
  }
})

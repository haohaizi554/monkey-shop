import { AxiosError, AxiosHeaders, type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/api/http'

const clearLocalSession = vi.fn()
const refreshGenerationStorageKey = 'monkeyshop:auth-refresh-generation:v1'
const refreshLeaseStorageKey = 'monkeyshop:auth-refresh-lease:v1'

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ clearLocalSession }),
}))

function response(config: InternalAxiosRequestConfig) {
  return {
    config,
    data: { code: 'OK', data: { url: config.url } },
    headers: new AxiosHeaders(),
    status: 200,
    statusText: 'OK',
  }
}

function unauthorized(config: InternalAxiosRequestConfig) {
  return new AxiosError('Unauthorized', AxiosError.ERR_BAD_REQUEST, config, undefined, {
    config,
    data: {},
    headers: new AxiosHeaders(),
    status: 401,
    statusText: 'Unauthorized',
  })
}

describe('HTTP authentication recovery', () => {
  const originalAdapter = http.defaults.adapter
  const originalLocksDescriptor = Object.getOwnPropertyDescriptor(navigator, 'locks')

  function installLocks(value: unknown) {
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value,
    })
  }

  beforeEach(() => {
    clearLocalSession.mockClear()
    localStorage.clear()
    installLocks(undefined)
    window.history.replaceState({}, '', '/login')
  })

  afterEach(() => {
    http.defaults.adapter = originalAdapter
    if (originalLocksDescriptor) {
      Object.defineProperty(navigator, 'locks', originalLocksDescriptor)
    } else {
      Reflect.deleteProperty(navigator, 'locks')
    }
  })

  it('shares one refresh across concurrent 401 responses without clearing the recovered session', async () => {
    let refreshAttempts = 0
    const adapter: AxiosAdapter = async (config) => {
      if (config.url === '/auth/refresh') {
        refreshAttempts += 1
        if (refreshAttempts > 1) {
          throw unauthorized(config)
        }
        await new Promise((resolve) => setTimeout(resolve, 0))
        return response(config)
      }

      if (!('_retry' in config)) {
        throw unauthorized(config)
      }
      return response(config)
    }
    http.defaults.adapter = adapter

    const results = await Promise.allSettled([
      http.get('/protected/orders'),
      http.get('/protected/profile'),
    ])

    expect(refreshAttempts).toBe(1)
    expect(results.map(({ status }) => status)).toEqual(['fulfilled', 'fulfilled'])
    expect(clearLocalSession).not.toHaveBeenCalled()
  })

  it('uses Web Locks and skips refresh when another tab advanced the session generation', async () => {
    let refreshAttempts = 0
    let protectedAttempts = 0
    const lockRequest = vi.fn(async (_name: string, callback: () => Promise<void>) => {
      localStorage.setItem(refreshGenerationStorageKey, 'other-tab-refresh')
      return callback()
    })
    installLocks({ request: lockRequest })
    http.defaults.adapter = async (config) => {
      if (config.url === '/auth/refresh') {
        refreshAttempts += 1
        return response(config)
      }
      protectedAttempts += 1
      if (protectedAttempts === 1) {
        throw unauthorized(config)
      }
      return response(config)
    }

    await http.get('/protected/orders')

    expect(lockRequest).toHaveBeenCalledTimes(1)
    expect(refreshAttempts).toBe(0)
    expect(protectedAttempts).toBe(2)
    expect(clearLocalSession).not.toHaveBeenCalled()
  })

  it('falls back to a localStorage lease and publishes a successful refresh generation', async () => {
    let refreshAttempts = 0
    http.defaults.adapter = async (config) => {
      if (config.url === '/auth/refresh') {
        refreshAttempts += 1
        return response(config)
      }
      if (!('_retry' in config)) {
        throw unauthorized(config)
      }
      return response(config)
    }

    await http.get('/protected/profile')

    expect(refreshAttempts).toBe(1)
    expect(localStorage.getItem(refreshGenerationStorageKey)).toBeTruthy()
    expect(localStorage.getItem(refreshLeaseStorageKey)).toBeNull()
    expect(clearLocalSession).not.toHaveBeenCalled()
  })

  it('falls back when Web Locks rejects before entering the critical section', async () => {
    let refreshAttempts = 0
    const lockRequest = vi
      .fn()
      .mockRejectedValue(new DOMException('Locks unavailable', 'NotAllowedError'))
    installLocks({ request: lockRequest })
    http.defaults.adapter = async (config) => {
      if (config.url === '/auth/refresh') {
        refreshAttempts += 1
        return response(config)
      }
      if (!('_retry' in config)) {
        throw unauthorized(config)
      }
      return response(config)
    }

    await http.get('/protected/profile')

    expect(lockRequest).toHaveBeenCalledTimes(1)
    expect(refreshAttempts).toBe(1)
    expect(localStorage.getItem(refreshGenerationStorageKey)).toBeTruthy()
    expect(clearLocalSession).not.toHaveBeenCalled()
  })

  it('waits on the fallback lease and reuses another tab successful refresh', async () => {
    let refreshAttempts = 0
    let protectedAttempts = 0
    localStorage.setItem(refreshGenerationStorageKey, 'before-refresh')
    localStorage.setItem(
      refreshLeaseStorageKey,
      JSON.stringify({ owner: 'other-tab', expiresAt: Date.now() + 1_000 }),
    )
    const otherTabRefresh = setTimeout(() => {
      localStorage.setItem(refreshGenerationStorageKey, 'after-refresh')
      localStorage.removeItem(refreshLeaseStorageKey)
    }, 0)
    http.defaults.adapter = async (config) => {
      if (config.url === '/auth/refresh') {
        refreshAttempts += 1
        return response(config)
      }
      protectedAttempts += 1
      if (protectedAttempts === 1) {
        throw unauthorized(config)
      }
      return response(config)
    }

    await http.get('/protected/orders')
    clearTimeout(otherTabRefresh)

    expect(refreshAttempts).toBe(0)
    expect(protectedAttempts).toBe(2)
    expect(clearLocalSession).not.toHaveBeenCalled()
  })

  it('does not clear a newer session when an expired fallback owner fails late', async () => {
    let protectedAttempts = 0
    let releaseLateRefresh!: () => void
    let markRefreshStarted!: () => void
    const lateRefreshGate = new Promise<void>((resolve) => {
      releaseLateRefresh = resolve
    })
    const refreshStarted = new Promise<void>((resolve) => {
      markRefreshStarted = resolve
    })
    http.defaults.adapter = async (config) => {
      if (config.url === '/auth/refresh') {
        markRefreshStarted()
        await lateRefreshGate
        throw unauthorized(config)
      }
      protectedAttempts += 1
      if (protectedAttempts === 1) {
        throw unauthorized(config)
      }
      return response(config)
    }

    const pendingRequest = http.get('/protected/orders')
    await refreshStarted
    localStorage.setItem(
      refreshLeaseStorageKey,
      JSON.stringify({ owner: 'resumed-tab', expiresAt: Date.now() + 30_000 }),
    )
    localStorage.setItem(refreshGenerationStorageKey, 'resumed-tab-success')
    releaseLateRefresh()

    await expect(pendingRequest).resolves.toMatchObject({ status: 200 })
    expect(protectedAttempts).toBe(2)
    expect(clearLocalSession).not.toHaveBeenCalled()
  })
})

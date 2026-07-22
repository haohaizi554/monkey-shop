import { describe, expect, test, vi } from 'vitest'

import { retryNetworkChanged } from './retryNetworkChanged'

describe('retryNetworkChanged', () => {
  test('retries transient Chromium network-change failures', async () => {
    const operation = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(new Error('page.goto: net::ERR_NETWORK_CHANGED'))
      .mockResolvedValue('loaded')

    await expect(retryNetworkChanged(operation, 3, 0)).resolves.toBe('loaded')
    expect(operation).toHaveBeenCalledTimes(2)
  })

  test('does not retry unrelated navigation failures', async () => {
    const failure = new Error('page.goto: net::ERR_CONNECTION_REFUSED')
    const operation = vi.fn<() => Promise<string>>().mockRejectedValue(failure)

    await expect(retryNetworkChanged(operation, 3, 0)).rejects.toBe(failure)
    expect(operation).toHaveBeenCalledTimes(1)
  })
})

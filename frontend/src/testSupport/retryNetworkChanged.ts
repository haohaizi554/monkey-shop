const NETWORK_CHANGED_ERROR = 'net::ERR_NETWORK_CHANGED'

export async function retryNetworkChanged<T>(
  operation: () => Promise<T>,
  maxAttempts = 3,
  delayMs = 1_000,
): Promise<T> {
  for (let attempt = 1; ; attempt += 1) {
    try {
      return await operation()
    } catch (error) {
      const isNetworkChange =
        error instanceof Error && error.message.includes(NETWORK_CHANGED_ERROR)
      if (!isNetworkChange || attempt >= maxAttempts) throw error
      if (delayMs > 0) await new Promise((resolve) => setTimeout(resolve, delayMs))
    }
  }
}

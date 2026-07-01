/// <reference types="vite/client" />

interface Window {
  axe: {
    run: (
      context: Document,
      options?: {
        runOnly?: {
          type: string
          values: string[]
        }
        resultTypes?: string[]
      },
    ) => Promise<{ violations: unknown[] }>
  }
}

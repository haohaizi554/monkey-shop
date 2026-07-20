import fs from 'node:fs/promises'
import http from 'node:http'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

import { launch } from 'chrome-launcher'
import lighthouse from 'lighthouse'
import desktopConfig from 'lighthouse/core/config/desktop-config.js'
import { preview } from 'vite'

import { collectLighthouseFailures, resolveMockApiRequest } from './lighthouse-gate.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const reportPath = path.join(root, 'lighthouse-report.json')
const url = 'http://127.0.0.1:4173/shop'

function withTimeout(promise, label, milliseconds = 5000) {
  let timer
  const timeout = new Promise((resolve) => {
    timer = setTimeout(() => {
      console.warn(`${label} cleanup timed out`)
      resolve()
    }, milliseconds)
  })
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer))
}

function closeHttpServer(server) {
  return new Promise((resolve, reject) => {
    server.close((error) => {
      if (error) {
        reject(error)
        return
      }
      resolve()
    })
    server.closeIdleConnections?.()
    server.closeAllConnections?.()
  })
}

function closePreview(server) {
  return closeHttpServer(server.httpServer)
}

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      server.off('error', reject)
      resolve()
    })
  })
}

function json(response, body, status = 200) {
  response.writeHead(status, { 'content-type': 'application/json' })
  response.end(JSON.stringify(body))
}

async function closeChrome(chrome) {
  try {
    await chrome.kill()
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    if (!message.includes('EPERM')) {
      console.warn(`Chrome cleanup warning: ${message}`)
    }
  }
}

async function cleanup(chrome, server, mockApi) {
  const cleanupTasks = [
    withTimeout(closeChrome(chrome), 'Chrome'),
    withTimeout(closePreview(server), 'Vite preview'),
    withTimeout(closeHttpServer(mockApi), 'mock API'),
  ]
  await Promise.allSettled(cleanupTasks)
}

const mockApi = http.createServer((request, response) => {
  const mockResponse = resolveMockApiRequest(request.method ?? 'GET', request.url ?? '/')
  if (mockResponse) {
    json(response, mockResponse.body, mockResponse.status)
    return
  }

  const pathname = new URL(request.url ?? '/', 'http://127.0.0.1').pathname
  if (pathname === '/images/default_product.jpg') {
    response.writeHead(200, { 'content-type': 'image/svg+xml' })
    response.end(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 3"><rect width="4" height="3" fill="#d9e2ec"/></svg>',
    )
    return
  }
  response.writeHead(404)
  response.end()
})

await listen(mockApi)
const mockAddress = mockApi.address()
const mockOrigin = `http://127.0.0.1:${mockAddress.port}`
const server = await preview({
  root,
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
    proxy: {
      '/api': {
        target: mockOrigin,
        changeOrigin: true,
      },
      '/images': {
        target: mockOrigin,
        changeOrigin: true,
      },
    },
  },
})
const chrome = await launch({
  chromePath: process.env.CHROME_PATH || undefined,
  chromeFlags: ['--headless=new', '--no-sandbox', '--disable-gpu'],
})

let exitCode = 0
try {
  const result = await lighthouse(
    url,
    {
      port: chrome.port,
      output: 'json',
      onlyCategories: ['performance', 'accessibility', 'best-practices', 'seo'],
      logLevel: 'error',
    },
    desktopConfig,
  )

  if (!result) {
    throw new Error('Lighthouse did not return a result')
  }

  const report = result.lhr
  await fs.writeFile(reportPath, JSON.stringify(report, null, 2))

  const scores = Object.fromEntries(
    Object.entries(report.categories).map(([name, category]) => [name, category.score ?? 0]),
  )
  const lcp = report.audits['largest-contentful-paint']?.numericValue ?? Number.POSITIVE_INFINITY
  const failures = collectLighthouseFailures(report)

  console.table(
    Object.fromEntries(
      Object.entries(scores).map(([name, score]) => [name, `${Math.round(score * 100)}`]),
    ),
  )
  console.log('Preset: desktop')
  console.log(`LCP: ${Math.round(lcp)}ms`)
  console.log(`Report: ${path.relative(root, reportPath)}`)

  if (failures.length > 0) {
    throw new Error(`Lighthouse gate failed: ${failures.join(', ')}`)
  }
} catch (error) {
  exitCode = 1
  console.error(error)
} finally {
  await cleanup(chrome, server, mockApi)
}

process.exit(exitCode)

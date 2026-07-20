import assert from 'node:assert/strict'
import test from 'node:test'

import { collectLighthouseFailures, resolveMockApiRequest } from './lighthouse-gate.mjs'

function passingReport() {
  return {
    categories: {
      performance: { score: 0.97 },
      accessibility: { score: 1 },
      'best-practices': { score: 1 },
      seo: { score: 1 },
    },
    audits: {
      'largest-contentful-paint': { numericValue: 1200 },
      'errors-in-console': { score: 1 },
      'network-requests': { details: { items: [] } },
    },
  }
}

test('mock API routing ignores the query string', () => {
  const response = resolveMockApiRequest('GET', '/api/v1/monkeys?page=0&size=100')

  assert.equal(response?.status, 200)
  assert.equal(response?.body.data.content.length, 1)
  assert.equal(response?.body.data.size, 100)
})

test('mock API covers the home page support requests', () => {
  assert.equal(resolveMockApiRequest('GET', '/api/v1/catalog/categories/tree')?.status, 200)
  assert.equal(resolveMockApiRequest('POST', '/api/v1/tracking/events')?.status, 200)
  assert.equal(resolveMockApiRequest('DELETE', '/api/v1/tracking/events'), null)
})

test('gate rejects console errors and failed API requests', () => {
  const report = passingReport()
  report.audits['errors-in-console'].score = 0
  report.audits['network-requests'].details.items = [
    { url: 'http://127.0.0.1:4173/api/v1/monkeys', statusCode: 503 },
    { url: 'http://127.0.0.1:4173/missing-image.jpg', statusCode: 404 },
  ]

  assert.deepEqual(collectLighthouseFailures(report), [
    'errors-in-console=0',
    'api-http=503 GET /api/v1/monkeys',
  ])
})

test('gate accepts a clean report', () => {
  assert.deepEqual(collectLighthouseFailures(passingReport()), [])
})

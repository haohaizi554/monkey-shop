const apiPrefix = '/api/'

function ok(data) {
  return {
    status: 200,
    body: {
      code: 'OK',
      message: 'ok',
      data,
    },
  }
}

export function resolveMockApiRequest(method, requestUrl) {
  const pathname = new URL(requestUrl, 'http://127.0.0.1').pathname
  const route = `${method.toUpperCase()} ${pathname}`

  if (route === 'GET /api/v1/users/me') {
    return ok({ isLogin: false })
  }

  if (route === 'GET /api/v1/monkeys') {
    return ok({
      content: [
        {
          id: 1,
          name: 'Golden Snub-nosed',
          breed: 'Rhinopithecus roxellana',
          price: '128.00',
          description: 'Healthy and ready for browsing.',
          imageUrl: '/images/default_product.jpg',
          stock: 3,
        },
      ],
      page: 0,
      size: 100,
      totalElements: 1,
      totalPages: 1,
      first: true,
      last: true,
    })
  }

  if (route === 'GET /api/v1/catalog/categories/tree') {
    return ok([])
  }

  if (route === 'POST /api/v1/tracking/events') {
    return ok({ id: 1, eventType: 'PAGE_VIEW' })
  }

  return null
}

function requestFailure(item) {
  if (!Number.isFinite(item?.statusCode) || item.statusCode < 400) {
    return null
  }

  try {
    const requestUrl = new URL(item.url)
    if (!requestUrl.pathname.startsWith(apiPrefix)) {
      return null
    }
    return `api-http=${item.statusCode} ${item.method ?? 'GET'} ${requestUrl.pathname}`
  } catch {
    return null
  }
}

export function collectLighthouseFailures(report, minimumScore = 0.95, maximumLcp = 2500) {
  const failures = Object.entries(report.categories ?? {})
    .filter(([, category]) => (category.score ?? 0) < minimumScore)
    .map(([name, category]) => `${name}=${Math.round((category.score ?? 0) * 100)}`)

  const lcp = report.audits?.['largest-contentful-paint']?.numericValue
  if (!Number.isFinite(lcp) || lcp > maximumLcp) {
    failures.push(`lcp=${Number.isFinite(lcp) ? Math.round(lcp) : 'missing'}ms`)
  }

  const consoleScore = report.audits?.['errors-in-console']?.score
  if (consoleScore !== 1) {
    failures.push(`errors-in-console=${consoleScore ?? 'missing'}`)
  }

  const networkItems = report.audits?.['network-requests']?.details?.items ?? []
  const networkFailures = networkItems.map(requestFailure).filter(Boolean)
  failures.push(...new Set(networkFailures))

  return failures
}

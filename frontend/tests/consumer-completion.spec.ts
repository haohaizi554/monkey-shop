import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const consumerViews = [
  'LoginView.vue',
  'ShopView.vue',
  'ProductDetailView.vue',
  'SearchView.vue',
  'RecommendView.vue',
  'CartView.vue',
  'CheckoutView.vue',
  'OrdersView.vue',
  'PaymentView.vue',
  'LogisticsView.vue',
  'ReviewView.vue',
  'MembershipView.vue',
  'ProfileView.vue',
]

test('consumer views use app feedback and semantic styling only', async () => {
  const viewsDirectory = resolve(process.cwd(), 'src/views')

  for (const view of consumerViews) {
    const source = await readFile(resolve(viewsDirectory, view), 'utf8')
    expect(source, `${view} must not own global Element feedback`).not.toMatch(
      /ElMessage|ElNotification/,
    )
    expect(source, `${view} must not render raw exception messages`).not.toMatch(
      /(?:notify\.(?:error|warning)|showAuthNotice)\([^\n]*error\.message|\{\{[^}]*error\.message/,
    )
    expect(source, `${view} must consume semantic color tokens`).not.toMatch(
      /#[0-9a-f]{3}(?:[0-9a-f]{3})?(?:[0-9a-f]{2})?\b|rgba?\(/i,
    )
  }
})

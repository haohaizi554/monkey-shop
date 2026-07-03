import type { Monkey } from '@/types'

const siteOrigin = 'https://monkeyshop.example.com'

function absoluteUrl(value?: string): string {
  if (!value) {
    return `${siteOrigin}/images/default_product.png`
  }
  if (value.startsWith('https://') || value.startsWith('http://')) {
    return value
  }
  return `${siteOrigin}${value.startsWith('/') ? '' : '/'}${value}`
}

export function productJsonLd(monkey: Monkey) {
  const selectedSku =
    monkey.skus?.find((sku) => sku.id === monkey.selectedSkuId) ??
    monkey.skus?.find((sku) => sku.active)
  const offerPrice = selectedSku?.memberPrice ?? selectedSku?.originalPrice ?? monkey.price
  return {
    '@context': 'https://schema.org',
    '@type': 'Product',
    name: monkey.name,
    description: monkey.description || `${monkey.name} ${monkey.breed}`,
    image: absoluteUrl(monkey.imageUrl),
    sku: selectedSku?.skuCode ?? `monkey-${monkey.id}`,
    category: monkey.categoryName ?? monkey.breed,
    offers: {
      '@type': 'Offer',
      url: `${siteOrigin}/shop/${monkey.id}`,
      priceCurrency: 'CNY',
      price: String(offerPrice),
      availability:
        monkey.stock > 0 ? 'https://schema.org/InStock' : 'https://schema.org/OutOfStock',
    },
  }
}

export function productListJsonLd(monkeys: readonly Monkey[]) {
  return {
    '@context': 'https://schema.org',
    '@type': 'ItemList',
    itemListElement: monkeys.map((monkey, index) => ({
      '@type': 'ListItem',
      position: index + 1,
      item: productJsonLd(monkey),
    })),
  }
}

export function serializeJsonLd(value: unknown): string {
  return JSON.stringify(value)
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
    .replace(/&/g, '\\u0026')
}

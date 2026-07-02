<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { listMonkeys } from '@/api/catalog'
import AppShell from '@/components/AppShell.vue'
import ProductImage from '@/components/ProductImage.vue'
import { useCheckout } from '@/composables/useCheckout'
import { productListJsonLd } from '@/seo/product-json-ld'
import { useJsonLd } from '@/seo/useJsonLd'
import type { Address, Monkey } from '@/types'
import { money } from '@/utils/format'

type NoticeLevel = 'error' | 'success' | 'warning'

const loading = ref(false)
const monkeys = ref<Monkey[]>([])
const filters = reactive({ keyword: '', minPrice: '', maxPrice: '', inStockOnly: false })
const { t } = useI18n()
const notice = ref<{ level: NoticeLevel; message: string } | null>(null)
let noticeTimer: ReturnType<typeof setTimeout> | undefined

function addressLabel(address: Address) {
  return `${address.receiverName} - ${address.phone} - ${address.detailAddress}`
}

function showNotice(level: NoticeLevel, message: string) {
  notice.value = { level, message }
  if (noticeTimer !== undefined) {
    clearTimeout(noticeTimer)
  }
  noticeTimer = setTimeout(() => {
    notice.value = null
  }, 4000)
}

const {
  openingCheckoutId,
  submittingOrder,
  checkoutOpen,
  addresses,
  selectedMonkey,
  selectedAddressId,
  newAddress,
  openCheckout,
  saveAddress,
  submitOrder,
} = useCheckout({ afterOrderCreated: loadMonkeys, notify: showNotice })

const filteredMonkeys = computed(() =>
  monkeys.value.filter((monkey) => {
    const keyword = filters.keyword.trim().toLowerCase()
    const price = Number(monkey.price)
    return (
      (!keyword ||
        monkey.name.toLowerCase().includes(keyword) ||
        monkey.breed.toLowerCase().includes(keyword)) &&
      (!filters.minPrice || price >= Number(filters.minPrice)) &&
      (!filters.maxPrice || price <= Number(filters.maxPrice)) &&
      (!filters.inStockOnly || monkey.stock > 0)
    )
  }),
)
const productListStructuredData = computed(() => productListJsonLd(filteredMonkeys.value))
useJsonLd('monkeyshop-product-list-jsonld', productListStructuredData)

async function loadMonkeys() {
  loading.value = true
  try {
    monkeys.value = await listMonkeys()
  } catch (error) {
    showNotice('error', error instanceof Error ? error.message : t('common.unableToLoadCatalog'))
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadMonkeys()
})
</script>

<template>
  <AppShell>
    <div v-if="notice" class="notice" :class="`notice-${notice.level}`" role="status">
      {{ notice.message }}
    </div>

    <section class="toolbar-band">
      <div>
        <h1>{{ $t('shop.title') }}</h1>
        <p>{{ $t('shop.subtitle') }}</p>
      </div>
      <div class="catalog-tools">
        <input
          id="catalog-keyword"
          v-model="filters.keyword"
          :aria-label="$t('common.search')"
          class="native-input"
          :placeholder="$t('common.search')"
        />
        <input
          id="catalog-min-price"
          v-model="filters.minPrice"
          :aria-label="$t('common.minPrice')"
          class="native-input"
          type="number"
          :placeholder="$t('common.minPrice')"
        />
        <input
          id="catalog-max-price"
          v-model="filters.maxPrice"
          :aria-label="$t('common.maxPrice')"
          class="native-input"
          type="number"
          :placeholder="$t('common.maxPrice')"
        />
        <div class="native-checkbox">
          <input
            v-model="filters.inStockOnly"
            type="checkbox"
            :aria-label="$t('shop.inStockOnly')"
          />
          <span>{{ $t('shop.inStockOnly') }}</span>
        </div>
      </div>
    </section>

    <div v-if="loading" class="skeleton-grid" aria-busy="true">
      <div v-for="item in 6" :key="item" class="skeleton-card" />
    </div>

    <div v-else class="product-grid">
      <article v-for="monkey in filteredMonkeys" :key="monkey.id" class="product-card">
        <RouterLink :to="`/shop/${monkey.id}`" :aria-label="monkey.name">
          <ProductImage :src="monkey.imageUrl" :alt="monkey.name" />
        </RouterLink>
        <div class="product-body">
          <div class="product-heading">
            <div>
              <RouterLink :to="`/shop/${monkey.id}`">
                <h2>{{ monkey.name }}</h2>
              </RouterLink>
              <p>{{ monkey.breed }}</p>
            </div>
            <strong>{{ money(monkey.price) }}</strong>
          </div>
          <p class="description">
            {{ monkey.description }}
          </p>
          <div class="product-actions">
            <span class="stock-pill" :class="{ 'stock-pill-muted': monkey.stock <= 0 }">
              {{ $t('common.stock') }} {{ monkey.stock }}
            </span>
            <button
              class="primary-button"
              type="button"
              :disabled="monkey.stock <= 0 || openingCheckoutId !== null"
              @click="openCheckout(monkey)"
            >
              {{
                openingCheckoutId === monkey.id
                  ? $t('common.loading')
                  : monkey.stock > 0
                    ? $t('shop.buy')
                    : $t('shop.soldOut')
              }}
            </button>
          </div>
        </div>
      </article>
    </div>

    <div v-if="checkoutOpen" class="modal-backdrop" role="presentation">
      <section
        class="checkout-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="$t('shop.checkout')"
      >
        <div class="section-title">
          <h2>{{ $t('shop.checkout') }}</h2>
          <button class="text-button" type="button" @click="checkoutOpen = false">
            {{ $t('common.cancel') }}
          </button>
        </div>

        <div v-if="selectedMonkey" class="checkout-summary">
          <ProductImage :src="selectedMonkey.imageUrl" :alt="selectedMonkey.name" />
          <div>
            <h2>{{ selectedMonkey.name }}</h2>
            <p>{{ selectedMonkey.breed }}</p>
            <strong>{{ money(selectedMonkey.price) }}</strong>
          </div>
        </div>

        <div class="address-list">
          <div v-for="address in addresses" :key="address.id" class="address-option">
            <input
              v-model="selectedAddressId"
              type="radio"
              :value="address.id"
              :aria-label="addressLabel(address)"
            />
            <span>{{ addressLabel(address) }}</span>
          </div>
        </div>

        <div class="divider">{{ $t('shop.addAddress') }}</div>
        <div class="inline-form">
          <input
            v-model="newAddress.receiverName"
            class="native-input"
            :placeholder="$t('common.receiver')"
          />
          <input v-model="newAddress.phone" class="native-input" :placeholder="$t('auth.phone')" />
          <input
            v-model="newAddress.detailAddress"
            class="native-input"
            :placeholder="$t('common.address')"
          />
          <button class="secondary-button" type="button" @click="saveAddress">
            {{ $t('common.save') }}
          </button>
        </div>

        <div class="checkout-footer">
          <button class="text-button" type="button" @click="checkoutOpen = false">
            {{ $t('common.cancel') }}
          </button>
          <button
            class="primary-button"
            type="button"
            :disabled="submittingOrder"
            @click="submitOrder"
          >
            {{ submittingOrder ? $t('common.loading') : $t('shop.placeOrder') }}
          </button>
        </div>
      </section>
    </div>
  </AppShell>
</template>

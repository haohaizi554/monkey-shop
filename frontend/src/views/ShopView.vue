<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import { computed, onMounted, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { listMonkeys } from '@/api/catalog'
import ProductImage from '@/components/ProductImage.vue'
import ProductCard from '@/components/product/ProductCard.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState, type AsyncStatus } from '@/composables/useAsyncState'
import { useCheckout } from '@/composables/useCheckout'
import { useNotify } from '@/composables/useNotify'
import { productListJsonLd } from '@/seo/product-json-ld'
import { useJsonLd } from '@/seo/useJsonLd'
import type { Address, Monkey } from '@/types'
import { money } from '@/utils/format'

type NoticeLevel = 'error' | 'success' | 'warning'

const router = useRouter()
const { t } = useI18n()
const filters = reactive({ keyword: '', minPrice: '', maxPrice: '', inStockOnly: false })
const notify = useNotify()
const catalogState = useAsyncState<Monkey[]>({ timeoutMs: 20000 })

function addressLabel(address: Address) {
  return `${address.receiverName} - ${address.phone} - ${address.detailAddress}`
}

function showNotice(level: NoticeLevel, message: string) {
  if (level === 'error') {
    notify.error(t('feedback.requestFailed'), { key: 'shop:checkout-error' })
    return
  }
  notify.notify(level, message)
}

async function loadMonkeys() {
  await catalogState.load(() => listMonkeys(), {
    isEmpty: (list) => list.length === 0,
  })
}

const {
  openingCheckoutId,
  submittingOrder,
  savingAddress,
  checkoutOpen,
  addresses,
  selectedMonkey,
  selectedAddressId,
  newAddress,
  openCheckout,
  saveAddress,
  submitOrder,
} = useCheckout({ afterOrderCreated: loadMonkeys, notify: showNotice })

const monkeysList = computed(() => catalogState.data.value ?? [])
const filteredMonkeys = computed(() =>
  monkeysList.value.filter((monkey) => {
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
const hasActiveFilters = computed(
  () =>
    filters.keyword.trim().length > 0 ||
    filters.minPrice.trim().length > 0 ||
    filters.maxPrice.trim().length > 0 ||
    filters.inStockOnly,
)
const catalogStatus = computed<AsyncStatus>(() => {
  if (catalogState.status.value === 'success' && filteredMonkeys.value.length === 0) {
    return 'empty'
  }
  return catalogState.status.value
})
const productListStructuredData = computed(() => productListJsonLd(filteredMonkeys.value))
useJsonLd('monkeyshop-product-list-jsonld', productListStructuredData)

function clearFilters() {
  filters.keyword = ''
  filters.minPrice = ''
  filters.maxPrice = ''
  filters.inStockOnly = false
}

function openProductDetails(productId: number) {
  void router.push(`/shop/${productId}`)
}

onMounted(() => {
  void loadMonkeys()
})
</script>

<template>
  <div class="route-view shop-view">
    <PageHeader :title="$t('shop.title')" :description="$t('shop.subtitle')" />

    <section class="catalog-toolbar" :aria-label="$t('common.search')">
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
        <label class="native-checkbox" for="catalog-in-stock">
          <input id="catalog-in-stock" v-model="filters.inStockOnly" type="checkbox" />
          <span>{{ $t('shop.inStockOnly') }}</span>
        </label>
      </div>
    </section>

    <AsyncStateView
      :status="catalogStatus"
      :error="catalogState.error.value"
      :empty-title="$t('shop.emptyTitle')"
      :empty-description="$t('shop.emptyDescription')"
      @retry="loadMonkeys"
    >
      <template #loading>
        <div class="skeleton-grid" aria-busy="true">
          <div v-for="item in 6" :key="item" class="skeleton-card" />
        </div>
      </template>

      <template #error>
        <div class="catalog-error" role="alert">
          <el-icon class="state-error-icon" aria-hidden="true"><Search /></el-icon>
          <p>{{ $t('common.unableToLoadCatalog') }}</p>
          <el-button type="primary" @click="loadMonkeys">{{ $t('common.retry') }}</el-button>
        </div>
      </template>

      <template #empty>
        <div class="empty-state" role="status">
          <Search class="empty-state-icon" aria-hidden="true" />
          <h2>{{ $t('shop.emptyTitle') }}</h2>
          <p>{{ $t('shop.emptyDescription') }}</p>
          <button
            v-if="hasActiveFilters"
            class="secondary-button"
            type="button"
            @click="clearFilters"
          >
            {{ $t('common.clearFilters') }}
          </button>
        </div>
      </template>

      <div class="product-grid">
        <ProductCard
          v-for="monkey in filteredMonkeys"
          :key="monkey.id"
          :product="monkey"
          :pending="openingCheckoutId === monkey.id"
          :disabled="monkey.stock <= 0 || openingCheckoutId !== null"
          :primary-action-label="monkey.stock > 0 ? $t('shop.buy') : $t('shop.soldOut')"
          @primary="openCheckout(monkey)"
          @secondary="openProductDetails(monkey.id)"
        />
      </div>
    </AsyncStateView>

    <el-dialog
      v-model="checkoutOpen"
      :title="$t('shop.checkout')"
      width="min(720px, 94vw)"
      :close-on-click-modal="!submittingOrder"
      :close-on-press-escape="!submittingOrder"
      :show-close="!submittingOrder"
    >
      <div v-if="selectedMonkey" class="checkout-summary">
        <ProductImage :src="selectedMonkey.imageUrl" :alt="selectedMonkey.name" />
        <div>
          <h2>{{ selectedMonkey.name }}</h2>
          <p>{{ selectedMonkey.breed }}</p>
          <strong>{{ money(selectedMonkey.price) }}</strong>
        </div>
      </div>

      <el-radio-group
        v-model="selectedAddressId"
        class="address-list"
        :disabled="submittingOrder || savingAddress"
      >
        <el-radio v-for="address in addresses" :key="address.id" :value="address.id" border>
          {{ addressLabel(address) }}
        </el-radio>
      </el-radio-group>

      <el-divider>{{ $t('shop.addAddress') }}</el-divider>
      <div class="inline-form">
        <el-input
          v-model="newAddress.receiverName"
          :disabled="submittingOrder || savingAddress"
          :aria-label="$t('common.receiver')"
          :placeholder="$t('common.receiver')"
        />
        <el-input
          v-model="newAddress.phone"
          :disabled="submittingOrder || savingAddress"
          :aria-label="$t('auth.phone')"
          :placeholder="$t('auth.phone')"
        />
        <el-input
          v-model="newAddress.detailAddress"
          :disabled="submittingOrder || savingAddress"
          :aria-label="$t('common.address')"
          :placeholder="$t('common.address')"
        />
        <el-button
          plain
          :loading="savingAddress"
          :disabled="savingAddress || submittingOrder"
          @click="saveAddress"
        >
          {{ $t('common.save') }}
        </el-button>
      </div>

      <template #footer>
        <el-button :disabled="submittingOrder" @click="checkoutOpen = false">
          {{ $t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="submittingOrder"
          :disabled="submittingOrder"
          @click="submitOrder"
        >
          {{ $t('shop.placeOrder') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.shop-view {
  display: grid;
  gap: var(--space-5);
}

.catalog-toolbar {
  min-width: 0;
  padding-block: var(--space-2);
}

.catalog-tools {
  grid-template-columns: minmax(220px, 2fr) minmax(110px, 1fr) minmax(110px, 1fr) auto;
  width: 100%;
}

.catalog-tools :where(.native-input, .native-checkbox) {
  min-height: 44px;
}

.catalog-error {
  display: grid;
  justify-items: center;
  gap: var(--space-3);
  min-height: 280px;
  align-content: center;
  color: var(--color-text-muted);
  text-align: center;
}

@media (max-width: 1080px) {
  .catalog-tools {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .catalog-tools {
    grid-template-columns: 1fr;
  }
}
</style>

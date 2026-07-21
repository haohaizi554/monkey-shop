<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { flattenCategoryTree, getCategoryTree, listMonkeyPage } from '@/api/catalog'
import type { PageEnvelope } from '@/api/page'
import ProductImage from '@/components/ProductImage.vue'
import MascotState from '@/components/mascot/MascotState.vue'
import ProductCard from '@/components/product/ProductCard.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState, type AsyncStatus } from '@/composables/useAsyncState'
import { useCheckout } from '@/composables/useCheckout'
import { useNotify } from '@/composables/useNotify'
import { productListJsonLd } from '@/seo/product-json-ld'
import { useJsonLd } from '@/seo/useJsonLd'
import type { Address, CategoryNode, Monkey } from '@/types'
import { money } from '@/utils/format'

type NoticeLevel = 'error' | 'success' | 'warning'
type PriceFilter = string | number

const router = useRouter()
const { t } = useI18n()
const filters = reactive({
  keyword: '',
  minPrice: '' as PriceFilter,
  maxPrice: '' as PriceFilter,
  inStockOnly: false,
})
const notify = useNotify()
const catalogState = useAsyncState<PageEnvelope<Monkey>>({ timeoutMs: 20000 })
const categoryState = useAsyncState<CategoryNode[]>({ timeoutMs: 10000 })
const currentPage = ref(0)
const pageSize = 12
let filterTimer: ReturnType<typeof setTimeout> | null = null

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

async function loadMonkeys(page = currentPage.value) {
  currentPage.value = page
  await catalogState.load(
    ({ signal }) =>
      listMonkeyPage({
        page,
        size: pageSize,
        sort: 'id,asc',
        keyword: filters.keyword.trim() || undefined,
        minPrice: String(filters.minPrice).trim() || undefined,
        maxPrice: String(filters.maxPrice).trim() || undefined,
        inStock: filters.inStockOnly || undefined,
        signal,
      }),
    {
      isEmpty: (result) => result.content.length === 0,
      preserveData: true,
    },
  )
}

function scheduleFilterReload() {
  if (filterTimer !== null) clearTimeout(filterTimer)
  currentPage.value = 0
  filterTimer = setTimeout(() => {
    filterTimer = null
    void loadMonkeys(0)
  }, 250)
}

function changePage(page: number) {
  void loadMonkeys(page - 1)
}

watch(filters, scheduleFilterReload, { deep: true })

onUnmounted(() => {
  if (filterTimer !== null) clearTimeout(filterTimer)
  catalogState.cancel()
  categoryState.cancel()
})

async function loadCategories() {
  await categoryState.load(() => getCategoryTree(), {
    isEmpty: (items) => items.length === 0,
  })
}

const {
  openingCheckoutId,
  submittingOrder,
  savingAddress,
  loadingAddresses,
  checkoutOpen,
  addresses,
  addressPageNumber,
  addressPageSize,
  addressTotal,
  selectedMonkey,
  selectedAddressId,
  newAddress,
  openCheckout,
  changeAddressPage,
  saveAddress,
  submitOrder,
} = useCheckout({ afterOrderCreated: loadMonkeys, notify: showNotice })

const catalogPage = computed(() => catalogState.data.value)
const monkeysList = computed(() => catalogPage.value?.content ?? [])
const categories = computed(() => flattenCategoryTree(categoryState.data.value ?? []))
const hasActiveFilters = computed(
  () =>
    filters.keyword.trim().length > 0 ||
    String(filters.minPrice).trim().length > 0 ||
    String(filters.maxPrice).trim().length > 0 ||
    filters.inStockOnly,
)
const catalogStatus = computed<AsyncStatus>(() => {
  return catalogState.status.value
})
const productListStructuredData = computed(() => productListJsonLd(monkeysList.value))
useJsonLd('monkeyshop-product-list-jsonld', productListStructuredData)

function clearFilters() {
  filters.keyword = ''
  filters.minPrice = ''
  filters.maxPrice = ''
  filters.inStockOnly = false
}

function openProductDetails(productId: string | number) {
  void router.push(`/shop/${productId}`)
}

onMounted(() => {
  void Promise.all([loadMonkeys(), loadCategories()])
})
</script>

<template>
  <div class="route-view shop-view">
    <PageHeader
      :title="$t('shop.title')"
      :description="$t('shop.subtitle')"
      :eyebrow="$t('nav.discover')"
    >
      <template #visual>
        <MascotState pose="shoppingBag" size="sm" decorative eager />
      </template>
    </PageHeader>

    <nav v-if="categories.length" class="category-rail" :aria-label="$t('shop.browseCategories')">
      <div class="category-rail__heading">
        <strong>{{ $t('shop.browseCategories') }}</strong>
        <RouterLink to="/search">{{ $t('shop.allCategories') }}</RouterLink>
      </div>
      <div class="category-rail__scroller">
        <RouterLink
          v-for="category in categories"
          :key="category.id"
          class="category-link"
          :to="{ path: '/search', query: { category: String(category.id) } }"
        >
          {{ category.name }}
        </RouterLink>
      </div>
    </nav>

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
          v-for="monkey in monkeysList"
          :key="monkey.id"
          :product="monkey"
          :pending="openingCheckoutId === monkey.id"
          :disabled="monkey.stock <= 0 || openingCheckoutId !== null"
          :primary-action-label="monkey.stock > 0 ? $t('shop.buy') : $t('shop.soldOut')"
          @primary="openCheckout(monkey)"
          @secondary="openProductDetails(monkey.id)"
        />
      </div>
      <el-pagination
        v-if="(catalogPage?.totalElements ?? 0) > pageSize"
        class="catalog-pagination"
        background
        layout="prev, pager, next, total"
        :current-page="currentPage + 1"
        :page-size="pageSize"
        :total="catalogPage?.totalElements ?? 0"
        @current-change="changePage"
      />
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
        :disabled="submittingOrder || savingAddress || loadingAddresses"
      >
        <el-radio v-for="address in addresses" :key="address.id" :value="address.id" border>
          {{ addressLabel(address) }}
        </el-radio>
      </el-radio-group>

      <el-pagination
        v-if="addressTotal > addressPageSize"
        class="quick-checkout-address-pagination"
        background
        layout="prev, pager, next, total"
        :current-page="addressPageNumber + 1"
        :page-size="addressPageSize"
        :total="addressTotal"
        :disabled="submittingOrder || savingAddress || loadingAddresses"
        @current-change="changeAddressPage"
      />

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

.catalog-pagination {
  min-height: 32px;
  justify-content: center;
  overflow-x: auto;
  padding-block: var(--space-2);
}

.quick-checkout-address-pagination {
  justify-content: center;
  margin-top: var(--space-3);
}

.category-rail {
  display: grid;
  gap: var(--space-3);
  min-width: 0;
  border-block: 1px solid var(--color-line);
  padding-block: var(--space-4);
}

.category-rail__heading {
  display: flex;
  gap: var(--space-4);
  align-items: center;
  justify-content: space-between;
  min-width: 0;
}

.category-rail__heading strong {
  color: var(--color-ink);
  font-size: var(--text-lg);
}

.category-rail__heading a {
  color: var(--color-primary);
  font-size: var(--text-sm);
  font-weight: 700;
}

.category-rail__scroller {
  display: flex;
  gap: var(--space-2);
  min-width: 0;
  overflow-x: auto;
  padding-bottom: var(--space-1);
  scrollbar-width: thin;
}

.category-link {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  min-height: 44px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-control);
  padding-inline: var(--space-4);
  color: var(--color-ink);
  background: var(--color-surface);
  font-weight: 700;
  transition:
    border-color var(--motion-fast),
    background-color var(--motion-fast),
    color var(--motion-fast);
}

.category-link:hover,
.category-link:focus-visible {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background: var(--color-primary-soft);
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

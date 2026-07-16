<script setup lang="ts">
import { DataAnalysis, Search, Star } from '@element-plus/icons-vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { flattenCategoryTree, getCategoryTree } from '@/api/catalog'
import * as searchApi from '@/api/search'
import MascotState from '@/components/mascot/MascotState.vue'
import ProductCard from '@/components/product/ProductCard.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { searchRouteQuerySchema, useRouteQueryState } from '@/composables/useRouteQueryState'
import type {
  CategoryNode,
  HotKeyword,
  Monkey,
  SearchPage,
  SearchProduct,
  SearchSuggestion,
} from '@/types'

interface SearchCardEntry {
  source: SearchProduct
  product: Monkey
}

const router = useRouter()
const { t } = useI18n()
const { state: query, replaceNow } = useRouteQueryState(searchRouteQuerySchema)
const resultState = useAsyncState<SearchPage>({ timeoutMs: 20000 })
const suggestionState = useAsyncState<SearchSuggestion[]>({ timeoutMs: 10000 })
const hotKeywordState = useAsyncState<HotKeyword[]>({ timeoutMs: 10000 })
const categoryState = useAsyncState<CategoryNode[]>({ timeoutMs: 10000 })
const resultHeadingRef = ref<HTMLElement>()
let searchTimer: ReturnType<typeof setTimeout> | undefined
let searchRequestVersion = 0

const products = computed(() => resultState.data.value?.content ?? [])
const suggestions = computed(() => suggestionState.data.value ?? [])
const hotKeywords = computed(() => hotKeywordState.data.value ?? [])
const categoryOptions = computed(() =>
  flattenCategoryTree(categoryState.data.value ?? []).map((category) => ({
    value: String(category.id),
    label: category.name,
  })),
)
const total = computed(() => resultState.data.value?.totalElements ?? 0)
const currentPage = computed(() => query.page + 1)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / query.size)))
const hasActiveFilters = computed(
  () =>
    Boolean(query.keyword.trim()) ||
    Boolean(query.category.trim()) ||
    Boolean(query.attribute.trim()) ||
    Boolean(query.value.trim()) ||
    query.sort !== 'RELEVANCE',
)
const activeFilters = computed(() => {
  const filters: Array<{ key: string; label: string }> = []
  if (query.keyword.trim()) {
    filters.push({ key: 'keyword', label: query.keyword.trim() })
  }
  if (query.category.trim()) {
    filters.push({
      key: 'category',
      label: `${t('search.categoryPlaceholder')}: ${categoryLabel(query.category)}`,
    })
  }
  if (query.attribute.trim()) {
    filters.push({
      key: 'attribute',
      label: `${t('search.attribute')}: ${query.attribute.trim()}`,
    })
  }
  if (query.value.trim()) {
    filters.push({ key: 'value', label: `${t('search.value')}: ${query.value.trim()}` })
  }
  if (query.sort !== 'RELEVANCE') {
    filters.push({ key: 'sort', label: sortLabel(query.sort) })
  }
  return filters
})
const searchCards = computed<SearchCardEntry[]>(() =>
  products.value.map((source) => ({
    source,
    product: {
      id: source.productId,
      name: source.name,
      breed: '',
      price: source.memberPrice ?? source.originalPrice,
      description: source.title || t('search.noTitle'),
      imageUrl: source.imageUrl ?? '',
      stock: Number.NaN,
    },
  })),
)

function sortLabel(sort: typeof query.sort): string {
  const keys = {
    RELEVANCE: 'search.sortRelevance',
    PRICE_ASC: 'search.sortPriceAsc',
    PRICE_DESC: 'search.sortPriceDesc',
    NEWEST: 'search.sortNewest',
    HOT: 'search.sortHot',
  } as const
  return t(keys[sort])
}

function categoryId(category: string): number | undefined {
  const parsed = Number.parseInt(category, 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
}

function categoryLabel(category: string): string {
  return categoryOptions.value.find((option) => option.value === category)?.label ?? category
}

async function loadCategories() {
  await categoryState.load(() => getCategoryTree(), {
    isEmpty: (items) => items.length === 0,
  })
}

async function loadHotKeywords() {
  await hotKeywordState.load(() => searchApi.hotKeywords(), {
    isEmpty: (items) => items.length === 0,
  })
}

async function loadSuggestions(keyword: string) {
  if (!keyword) {
    suggestionState.reset()
    return
  }
  await suggestionState.load(() => searchApi.searchSuggestions(keyword), {
    isEmpty: (items) => items.length === 0,
  })
}

async function searchProducts(options: { focusResults?: boolean } = {}) {
  const requestVersion = ++searchRequestVersion
  const keyword = query.keyword.trim()
  const params = {
    keyword,
    categoryId: categoryId(query.category),
    attributeKey: query.attribute.trim(),
    attributeValue: query.value.trim(),
    sort: query.sort,
    page: query.page,
    size: query.size,
  }
  const resultPromise = resultState.load(() => searchApi.searchProducts(params), {
    isEmpty: (page) => page.content.length === 0,
  })

  await Promise.all([resultPromise, loadSuggestions(keyword)])
  if (requestVersion !== searchRequestVersion) {
    return
  }
  if (options.focusResults) {
    await nextTick()
    resultHeadingRef.value?.focus({ preventScroll: true })
  }
}

function clearScheduledSearch() {
  if (searchTimer !== undefined) {
    clearTimeout(searchTimer)
    searchTimer = undefined
  }
}

function scheduleSearch() {
  clearScheduledSearch()
  searchTimer = setTimeout(() => {
    searchTimer = undefined
    void searchProducts()
  }, 250)
}

async function runSearchNow(options: { focusResults?: boolean; resetPage?: boolean } = {}) {
  if (options.resetPage) {
    query.page = 0
  }
  clearScheduledSearch()
  await replaceNow().catch(() => false)
  clearScheduledSearch()
  await searchProducts({ focusResults: options.focusResults })
}

async function clearFilters() {
  Object.assign(query, {
    keyword: '',
    category: '',
    attribute: '',
    value: '',
    sort: 'RELEVANCE' as const,
    page: 0,
    size: 12 as const,
  })
  await runSearchNow()
}

async function useKeyword(keyword: string) {
  query.keyword = keyword
  await runSearchNow({ resetPage: true })
}

function resetPage() {
  query.page = 0
}

async function openProduct(product: SearchProduct) {
  void searchApi
    .recordSearchConversion({
      keyword: query.keyword,
      productId: product.productId,
      source: 'search-result',
    })
    .catch(() => undefined)
  await router.push(`/shop/${product.productId}`)
}

async function onPageChange(page: number) {
  query.page = Math.max(0, page - 1)
  await runSearchNow({ focusResults: true })
}

watch(query, scheduleSearch, { deep: true, flush: 'sync' })

onMounted(() => {
  void Promise.all([loadHotKeywords(), loadCategories(), searchProducts()])
})

onBeforeUnmount(clearScheduledSearch)
</script>

<template>
  <div class="route-view search-page">
    <PageHeader :title="$t('nav.search')" :eyebrow="$t('search.discovery')">
      <template #actions>
        <RouterLink class="secondary-button" to="/recommendations">
          <el-icon><Star /></el-icon>
          <span>{{ $t('nav.recommend') }}</span>
        </RouterLink>
      </template>
    </PageHeader>

    <form class="search-toolbar" @submit.prevent="runSearchNow({ resetPage: true })">
      <el-input
        v-model="query.keyword"
        :aria-label="$t('search.keywordPlaceholder')"
        :placeholder="$t('search.keywordPlaceholder')"
        clearable
        @update:model-value="resetPage"
      />
      <el-select
        id="category-filter"
        v-model="query.category"
        :aria-label="$t('search.categoryPlaceholder')"
        :placeholder="$t('search.categoryPlaceholder')"
        :loading="categoryState.isLoading.value"
        clearable
        @change="resetPage"
      >
        <el-option
          v-for="category in categoryOptions"
          :key="category.value"
          :label="category.label"
          :value="category.value"
        />
      </el-select>
      <el-input
        v-model="query.attribute"
        :aria-label="$t('search.attribute')"
        :placeholder="$t('search.attribute')"
        clearable
        @update:model-value="resetPage"
      />
      <el-input
        v-model="query.value"
        :aria-label="$t('search.value')"
        :placeholder="$t('search.value')"
        clearable
        @update:model-value="resetPage"
      />
      <el-select v-model="query.sort" :aria-label="$t('search.sort')" @change="resetPage">
        <el-option :label="$t('search.sortRelevance')" value="RELEVANCE" />
        <el-option :label="$t('search.sortPriceAsc')" value="PRICE_ASC" />
        <el-option :label="$t('search.sortPriceDesc')" value="PRICE_DESC" />
        <el-option :label="$t('search.sortNewest')" value="NEWEST" />
        <el-option :label="$t('search.sortHot')" value="HOT" />
      </el-select>
      <el-button native-type="submit" type="primary" :icon="Search">
        {{ $t('common.search') }}
      </el-button>
      <el-button v-if="hasActiveFilters" native-type="button" @click="clearFilters">
        {{ $t('common.clearFilters') }}
      </el-button>

      <div class="search-toolbar__summary" aria-live="polite">
        <strong>{{ $t('search.matched', { total }) }}</strong>
        <span v-for="filter in activeFilters" :key="filter.key" class="filter-chip">
          {{ filter.label }}
        </span>
      </div>
    </form>

    <div v-if="hotKeywords.length" class="keyword-strip" :aria-label="$t('search.hotKeywords')">
      <button
        v-for="item in hotKeywords"
        :key="item.keyword"
        type="button"
        @click="useKeyword(item.keyword)"
      >
        {{ item.keyword }} <span>{{ item.score }}</span>
      </button>
    </div>

    <div v-if="suggestions.length" class="suggestions" :aria-label="$t('search.suggestions')">
      <button
        v-for="item in suggestions"
        :key="`${item.source}-${item.keyword}`"
        type="button"
        @click="useKeyword(item.keyword)"
      >
        {{ item.keyword }}
      </button>
    </div>

    <section class="result-section">
      <div class="result-heading">
        <h2 ref="resultHeadingRef" tabindex="-1">{{ $t('common.products') }}</h2>
        <span>{{ $t('search.matched', { total }) }}</span>
      </div>

      <AsyncStateView
        :status="resultState.status.value"
        :error="resultState.error.value"
        :empty-title="$t('search.emptyTitle')"
        :empty-description="$t('search.emptyDescription')"
        @retry="runSearchNow"
      >
        <template #loading>
          <div class="result-grid skeleton-grid" aria-busy="true">
            <div v-for="item in 4" :key="item" class="skeleton-card" />
          </div>
        </template>

        <template #error>
          <div class="search-error" role="alert">
            <DataAnalysis class="empty-state-icon" aria-hidden="true" />
            <p>{{ $t('search.unableToSearch') }}</p>
            <el-button type="primary" @click="runSearchNow">{{ $t('common.retry') }}</el-button>
          </div>
        </template>

        <template #empty>
          <div class="empty-state search-empty-state" role="status">
            <MascotState pose="search" size="md" :alt="$t('search.emptyMascotAlt')" />
            <h2>{{ $t('search.emptyTitle') }}</h2>
            <p>{{ $t('search.emptyDescription') }}</p>
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

        <div class="result-grid">
          <ProductCard
            v-for="entry in searchCards"
            :key="entry.source.productId"
            :product="entry.product"
            :primary-action-label="$t('common.open')"
            @primary="openProduct(entry.source)"
            @secondary="openProduct(entry.source)"
          />
        </div>
      </AsyncStateView>

      <div v-if="resultState.status.value !== 'error' && total > query.size" class="pagination-bar">
        <el-pagination
          :current-page="currentPage"
          :page-size="query.size"
          :total="total"
          :page-count="totalPages"
          layout="prev, pager, next, jumper, total"
          background
          @current-change="onPageChange"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.search-page {
  display: grid;
  gap: var(--space-5);
}

.search-toolbar {
  display: grid;
  grid-template-columns: minmax(180px, 2fr) repeat(4, minmax(120px, 1fr)) auto auto;
  gap: var(--space-3);
  align-items: center;
  min-width: 0;
  padding-block: var(--space-2);
}

.search-toolbar__summary {
  display: flex;
  grid-column: 1 / -1;
  flex-wrap: wrap;
  gap: var(--space-2);
  align-items: center;
  min-height: 32px;
  color: var(--color-text-muted);
}

.filter-chip {
  max-width: 100%;
  overflow: hidden;
  border-radius: var(--radius-pill);
  padding: var(--space-1) var(--space-2);
  color: var(--color-text);
  background: var(--color-surface-subtle);
  font-size: var(--text-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.keyword-strip,
.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.keyword-strip button,
.suggestions button {
  min-height: 44px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-pill);
  padding-inline: var(--space-3);
  color: var(--color-text);
  background: var(--color-surface);
  cursor: pointer;
}

.keyword-strip button:hover,
.suggestions button:hover {
  border-color: var(--color-brand);
}

.keyword-strip span {
  margin-left: var(--space-1);
  color: var(--color-text-muted);
}

.result-section {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
}

.result-heading {
  display: flex;
  gap: var(--space-4);
  align-items: center;
  justify-content: space-between;
}

.result-heading h2 {
  margin: 0;
}

.result-heading span {
  color: var(--color-text-muted);
  font-weight: 700;
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--space-4);
}

.search-error {
  display: grid;
  justify-items: center;
  gap: var(--space-3);
  min-height: 280px;
  align-content: center;
  color: var(--color-text-muted);
  text-align: center;
}

.search-empty-state :deep(.mascot-state) {
  width: min(168px, 54vw);
}

.pagination-bar {
  display: flex;
  max-width: 100%;
  overflow-x: auto;
  justify-content: center;
  padding-top: var(--space-3);
}

@media (max-width: 1080px) {
  .search-toolbar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .search-toolbar {
    grid-template-columns: 1fr;
  }

  .result-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .pagination-bar {
    justify-content: flex-start;
  }
}
</style>

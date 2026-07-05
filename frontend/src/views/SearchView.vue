<script setup lang="ts">
import { DataAnalysis, Search, Star } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import * as searchApi from '@/api/search'
import AppShell from '@/components/AppShell.vue'
import ProductImage from '@/components/ProductImage.vue'
import type { HotKeyword, SearchProduct, SearchSort, SearchSuggestion } from '@/types'
import { money } from '@/utils/format'

const router = useRouter()
const { t } = useI18n()
const loading = ref(false)
const products = ref<SearchProduct[]>([])
const suggestions = ref<SearchSuggestion[]>([])
const hot = ref<HotKeyword[]>([])
const total = ref(0)
const query = reactive({
  keyword: '',
  categoryId: undefined as number | undefined,
  attributeKey: '',
  attributeValue: '',
  sort: 'RELEVANCE' as SearchSort,
  page: 0,
  size: 12,
})

const hasActiveFilters = computed(
  () =>
    Boolean(query.keyword.trim()) ||
    Boolean(query.categoryId) ||
    Boolean(query.attributeKey.trim()) ||
    Boolean(query.attributeValue.trim()) ||
    query.sort !== 'RELEVANCE',
)

async function loadHot() {
  try {
    hot.value = await searchApi.hotKeywords()
  } catch {
    hot.value = []
  }
}

async function searchProducts() {
  loading.value = true
  try {
    const page = await searchApi.searchProducts({
      keyword: query.keyword,
      categoryId: query.categoryId,
      attributeKey: query.attributeKey,
      attributeValue: query.attributeValue,
      sort: query.sort,
      page: query.page,
      size: query.size,
    })
    products.value = page.content
    total.value = page.totalElements
    suggestions.value = query.keyword.trim() ? await searchApi.searchSuggestions(query.keyword) : []
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('search.unableToSearch'))
  } finally {
    loading.value = false
  }
}

async function clearFilters() {
  Object.assign(query, {
    keyword: '',
    categoryId: undefined,
    attributeKey: '',
    attributeValue: '',
    sort: 'RELEVANCE' as SearchSort,
    page: 0,
  })
  suggestions.value = []
  await searchProducts()
}

async function useKeyword(keyword: string) {
  query.keyword = keyword
  query.page = 0
  await searchProducts()
}

async function openProduct(product: SearchProduct) {
  await searchApi.recordSearchConversion({
    keyword: query.keyword,
    productId: product.productId,
    source: 'search-result',
  })
  await router.push(`/shop/${product.productId}`)
}

onMounted(async () => {
  await Promise.all([loadHot(), searchProducts()])
})
</script>

<template>
  <AppShell>
    <section class="search-page">
      <header class="page-heading">
        <div>
          <p class="profile-kicker">{{ $t('search.discovery') }}</p>
          <h1>{{ $t('nav.search') }}</h1>
        </div>
        <RouterLink class="secondary-button" to="/recommendations">
          <el-icon><Star /></el-icon>
          <span>{{ $t('nav.recommend') }}</span>
        </RouterLink>
      </header>

      <form class="search-toolbar" @submit.prevent="searchProducts">
        <el-input
          v-model="query.keyword"
          :placeholder="$t('search.keywordPlaceholder')"
          clearable
        />
        <el-input-number
          v-model="query.categoryId"
          :placeholder="$t('search.categoryPlaceholder')"
          :min="1"
          controls-position="right"
        />
        <el-input v-model="query.attributeKey" :placeholder="$t('search.attribute')" clearable />
        <el-input v-model="query.attributeValue" :placeholder="$t('search.value')" clearable />
        <el-select v-model="query.sort" :aria-label="$t('search.sort')">
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
      </form>

      <div v-if="hot.length" class="keyword-strip" :aria-label="$t('search.hotKeywords')">
        <button
          v-for="item in hot"
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

      <section v-loading="loading" class="result-section">
        <div class="result-heading">
          <h2>{{ $t('common.products') }}</h2>
          <span>{{ $t('search.matched', { total }) }}</span>
        </div>
        <div v-if="products.length" class="result-grid">
          <article v-for="product in products" :key="product.productId" class="product-tile">
            <ProductImage :src="product.imageUrl || '/favicon.svg'" :alt="product.name" />
            <div class="product-tile-body">
              <h3>{{ product.name }}</h3>
              <p>{{ product.title || $t('search.noTitle') }}</p>
              <strong>{{ money(product.memberPrice || product.originalPrice) }}</strong>
            </div>
            <el-button type="primary" plain @click="openProduct(product)">
              {{ $t('common.open') }}
            </el-button>
          </article>
        </div>
        <div v-else-if="!loading" class="empty-state">
          <DataAnalysis class="empty-state-icon" />
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
      </section>
    </section>
  </AppShell>
</template>

<style scoped>
.search-page {
  display: grid;
  gap: 20px;
}

.search-toolbar {
  display: grid;
  grid-template-columns: minmax(180px, 2fr) repeat(4, minmax(120px, 1fr)) auto auto;
  gap: 10px;
  align-items: center;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 14px;
  background: var(--panel);
  box-shadow: var(--shadow);
}

.keyword-strip,
.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.keyword-strip button,
.suggestions button {
  min-height: 34px;
  border: 1px solid var(--border-color);
  border-radius: 999px;
  padding: 0 12px;
  color: var(--text-color);
  background: var(--surface-color);
  cursor: pointer;
}

.keyword-strip button:hover,
.suggestions button:hover {
  border-color: color-mix(in srgb, var(--brand) 45%, var(--line));
}

.keyword-strip span {
  color: var(--text-muted);
  margin-left: 4px;
}

.result-section {
  display: grid;
  gap: 14px;
}

.result-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.result-heading span {
  color: var(--text-muted);
  font-weight: 700;
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
}

.product-tile {
  display: grid;
  gap: 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px;
  background: var(--surface-color);
  box-shadow: var(--shadow);
}

.product-tile-body {
  display: grid;
  gap: 8px;
}

.product-tile h3,
.product-tile p {
  margin: 0;
}

.product-tile p {
  min-height: 40px;
  color: var(--text-muted);
  line-height: 1.45;
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
}
</style>

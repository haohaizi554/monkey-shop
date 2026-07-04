<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as searchApi from '@/api/search'
import AppShell from '@/components/AppShell.vue'
import type { HotKeyword, SearchProduct, SearchSort, SearchSuggestion } from '@/types'

const router = useRouter()
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

async function loadHot() {
  hot.value = await searchApi.hotKeywords()
}

async function search() {
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
    if (query.keyword.trim()) {
      suggestions.value = await searchApi.searchSuggestions(query.keyword)
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Search failed')
  } finally {
    loading.value = false
  }
}

async function useKeyword(keyword: string) {
  query.keyword = keyword
  query.page = 0
  await search()
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
  await loadHot()
  await search()
})
</script>

<template>
  <AppShell>
    <section class="search-page">
      <header class="search-header">
        <div>
          <p class="eyebrow">Discovery</p>
          <h1>Search</h1>
        </div>
        <RouterLink class="secondary-button" to="/recommendations">Recommendations</RouterLink>
      </header>

      <form class="search-toolbar" @submit.prevent="search">
        <el-input v-model="query.keyword" placeholder="Keyword, product, breed" clearable />
        <el-input-number
          v-model="query.categoryId"
          placeholder="Category"
          :min="1"
          controls-position="right"
        />
        <el-input v-model="query.attributeKey" placeholder="Attribute" clearable />
        <el-input v-model="query.attributeValue" placeholder="Value" clearable />
        <el-select v-model="query.sort" aria-label="Sort">
          <el-option label="Relevance" value="RELEVANCE" />
          <el-option label="Price low" value="PRICE_ASC" />
          <el-option label="Price high" value="PRICE_DESC" />
          <el-option label="Newest" value="NEWEST" />
          <el-option label="Hot" value="HOT" />
        </el-select>
        <el-button native-type="submit" type="primary">Search</el-button>
      </form>

      <div class="keyword-strip">
        <button
          v-for="item in hot"
          :key="item.keyword"
          type="button"
          @click="useKeyword(item.keyword)"
        >
          {{ item.keyword }} <span>{{ item.score }}</span>
        </button>
      </div>

      <div v-if="suggestions.length" class="suggestions">
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
          <h2>Products</h2>
          <span>{{ total }} matched</span>
        </div>
        <div class="result-grid">
          <article v-for="product in products" :key="product.productId" class="product-tile">
            <img :src="product.imageUrl || '/favicon.svg'" :alt="product.name" />
            <div>
              <h3>{{ product.name }}</h3>
              <p>{{ product.title }}</p>
              <strong>¥{{ product.memberPrice || product.originalPrice }}</strong>
            </div>
            <el-button type="primary" plain @click="openProduct(product)">Open</el-button>
          </article>
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

.search-header,
.result-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.eyebrow {
  margin: 0 0 4px;
  color: var(--text-muted);
  font-size: 0.78rem;
  text-transform: uppercase;
}

h1,
h2,
h3,
p {
  margin: 0;
}

.search-toolbar {
  display: grid;
  grid-template-columns: minmax(180px, 2fr) repeat(4, minmax(120px, 1fr)) auto;
  gap: 10px;
  align-items: center;
}

.keyword-strip,
.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.keyword-strip button,
.suggestions button {
  border: 1px solid var(--border-color);
  background: var(--surface-color);
  color: var(--text-color);
  border-radius: 999px;
  padding: 6px 12px;
  cursor: pointer;
}

.keyword-strip span {
  color: var(--text-muted);
  margin-left: 4px;
}

.result-section {
  display: grid;
  gap: 14px;
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
}

.product-tile {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px;
  display: grid;
  gap: 10px;
  background: var(--surface-color);
}

.product-tile img {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  border-radius: 6px;
}

.product-tile p {
  color: var(--text-muted);
  min-height: 40px;
}

@media (max-width: 900px) {
  .search-toolbar {
    grid-template-columns: 1fr;
  }

  .search-header,
  .result-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>

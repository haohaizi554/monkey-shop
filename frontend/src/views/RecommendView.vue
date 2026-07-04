<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as searchApi from '@/api/search'
import AppShell from '@/components/AppShell.vue'
import type { Recommendation } from '@/types'

const router = useRouter()
const loading = ref(false)
const items = ref<Recommendation[]>([])
const profile = ref('')
const form = reactive({
  interestProfile: 'family shopping, premium service, fast delivery',
  tags: 'premium,fast,smart',
})

async function loadRecommendations() {
  loading.value = true
  try {
    items.value = await searchApi.recommendations()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Unable to load recommendations')
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  const saved = await searchApi.updateSearchProfile({
    interestProfile: form.interestProfile,
    tags: form.tags
      .split(',')
      .map((tag) => tag.trim())
      .filter(Boolean),
  })
  profile.value = saved.maskedInterestProfile
  ElMessage.success('Profile updated')
  await loadRecommendations()
}

async function openRecommendation(item: Recommendation) {
  await searchApi.recordSearchConversion({
    productId: item.productId,
    keyword: item.reason,
    source: 'recommendation',
  })
  await router.push(`/shop/${item.productId}`)
}

onMounted(loadRecommendations)
</script>

<template>
  <AppShell>
    <section class="recommend-page">
      <header class="recommend-header">
        <div>
          <p class="eyebrow">Personalized</p>
          <h1>Recommendations</h1>
        </div>
        <RouterLink class="secondary-button" to="/search">Search</RouterLink>
      </header>

      <section class="profile-panel">
        <el-input v-model="form.interestProfile" placeholder="Interest profile" />
        <el-input v-model="form.tags" placeholder="Tags, comma separated" />
        <el-button type="primary" @click="saveProfile">Save</el-button>
        <span v-if="profile">Stored as {{ profile }}</span>
      </section>

      <section v-loading="loading" class="recommend-grid">
        <article v-for="item in items" :key="item.productId" class="recommend-tile">
          <img :src="item.imageUrl || '/favicon.svg'" :alt="item.name" />
          <div>
            <h2>{{ item.name }}</h2>
            <p>{{ item.title }}</p>
            <span>{{ item.reason }} · {{ item.score }}</span>
          </div>
          <el-button type="primary" plain @click="openRecommendation(item)">Open</el-button>
        </article>
      </section>
    </section>
  </AppShell>
</template>

<style scoped>
.recommend-page {
  display: grid;
  gap: 20px;
}

.recommend-header {
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
p {
  margin: 0;
}

.profile-panel {
  display: grid;
  grid-template-columns: minmax(180px, 2fr) minmax(160px, 1fr) auto auto;
  gap: 10px;
  align-items: center;
}

.profile-panel span {
  color: var(--text-muted);
}

.recommend-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
}

.recommend-tile {
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px;
  display: grid;
  gap: 10px;
  background: var(--surface-color);
}

.recommend-tile img {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  border-radius: 6px;
}

.recommend-tile p,
.recommend-tile span {
  color: var(--text-muted);
}

@media (max-width: 760px) {
  .recommend-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .profile-panel {
    grid-template-columns: 1fr;
  }
}
</style>

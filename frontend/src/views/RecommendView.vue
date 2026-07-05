<script setup lang="ts">
import { Search, Star } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import * as searchApi from '@/api/search'
import AppShell from '@/components/AppShell.vue'
import ProductImage from '@/components/ProductImage.vue'
import type { Recommendation } from '@/types'

const router = useRouter()
const { t } = useI18n()
const loading = ref(false)
const saving = ref(false)
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
    ElMessage.error(error instanceof Error ? error.message : t('recommend.unableToLoad'))
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  if (saving.value) {
    return
  }
  saving.value = true
  try {
    const saved = await searchApi.updateSearchProfile({
      interestProfile: form.interestProfile,
      tags: form.tags
        .split(',')
        .map((tag) => tag.trim())
        .filter(Boolean),
    })
    profile.value = saved.maskedInterestProfile
    ElMessage.success(t('recommend.profileUpdated'))
    await loadRecommendations()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('recommend.unableToSave'))
  } finally {
    saving.value = false
  }
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
      <header class="page-heading">
        <div>
          <p class="profile-kicker">{{ $t('recommend.personalized') }}</p>
          <h1>{{ $t('nav.recommend') }}</h1>
        </div>
        <RouterLink class="secondary-button" to="/search">
          <el-icon><Search /></el-icon>
          <span>{{ $t('nav.search') }}</span>
        </RouterLink>
      </header>

      <form class="profile-panel" @submit.prevent="saveProfile">
        <el-input
          v-model="form.interestProfile"
          :placeholder="$t('recommend.profilePlaceholder')"
        />
        <el-input v-model="form.tags" :placeholder="$t('recommend.tagsPlaceholder')" />
        <el-button type="primary" native-type="submit" :loading="saving" :icon="Star">
          {{ $t('common.save') }}
        </el-button>
        <span v-if="profile">{{ $t('recommend.storedAs', { profile }) }}</span>
      </form>

      <section v-loading="loading" class="recommend-grid">
        <article v-for="item in items" :key="item.productId" class="recommend-tile">
          <ProductImage :src="item.imageUrl || '/favicon.svg'" :alt="item.name" />
          <div class="recommend-copy">
            <h2>{{ item.name }}</h2>
            <p>{{ item.title || $t('search.noTitle') }}</p>
            <span>{{ item.reason }} / {{ item.score }}</span>
          </div>
          <el-button type="primary" plain @click="openRecommendation(item)">
            {{ $t('common.open') }}
          </el-button>
        </article>
        <div v-if="!loading && items.length === 0" class="empty-state">
          <Star class="empty-state-icon" />
          <h2>{{ $t('recommend.emptyTitle') }}</h2>
          <p>{{ $t('recommend.emptyDescription') }}</p>
        </div>
      </section>
    </section>
  </AppShell>
</template>

<style scoped>
.recommend-page {
  display: grid;
  gap: 20px;
}

.profile-panel {
  display: grid;
  grid-template-columns: minmax(180px, 2fr) minmax(160px, 1fr) auto minmax(0, auto);
  gap: 10px;
  align-items: center;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 14px;
  background: var(--panel);
  box-shadow: var(--shadow);
}

.profile-panel span {
  color: var(--text-muted);
  font-weight: 700;
  overflow-wrap: anywhere;
}

.recommend-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
}

.recommend-tile {
  display: grid;
  gap: 12px;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px;
  background: var(--surface-color);
  box-shadow: var(--shadow);
}

.recommend-copy {
  display: grid;
  gap: 8px;
}

.recommend-copy h2,
.recommend-copy p {
  margin: 0;
}

.recommend-copy p,
.recommend-copy span {
  color: var(--text-muted);
  line-height: 1.45;
}

@media (max-width: 900px) {
  .profile-panel {
    grid-template-columns: 1fr;
  }
}
</style>

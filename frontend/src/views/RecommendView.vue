<script setup lang="ts">
import { Search, Star } from '@element-plus/icons-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import * as searchApi from '@/api/search'
import ProductCard from '@/components/product/ProductCard.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import type { Monkey, Recommendation } from '@/types'

interface RecommendationCardEntry {
  source: Recommendation
  product: Monkey
}

const router = useRouter()
const { t } = useI18n()
const notify = useNotify()
const recommendationState = useAsyncState<Recommendation[]>({ timeoutMs: 20000 })
const saving = ref(false)
const profile = ref('')
const profileError = ref<string | null>(null)
const form = reactive({ interestProfile: '', tags: '' })

const recommendationCards = computed<RecommendationCardEntry[]>(() =>
  (recommendationState.data.value ?? []).map((source) => ({
    source,
    product: {
      id: source.productId,
      name: source.name,
      breed: '',
      price: Number.NaN,
      description: source.title || t('search.noTitle'),
      imageUrl: source.imageUrl ?? '',
      stock: Number.NaN,
    },
  })),
)

async function loadRecommendations() {
  await recommendationState.load(() => searchApi.recommendations(), {
    isEmpty: (items) => items.length === 0,
  })
}

async function saveProfile() {
  if (saving.value) {
    return
  }
  saving.value = true
  profileError.value = null
  try {
    const saved = await searchApi.updateSearchProfile({
      interestProfile: form.interestProfile,
      tags: form.tags
        .split(',')
        .map((tag) => tag.trim())
        .filter(Boolean),
    })
    profile.value = saved.maskedInterestProfile
    notify.success(t('recommend.profileUpdated'), { key: 'recommend:profile-updated' })
    await loadRecommendations()
  } catch {
    profileError.value = 'recommend.unableToSave'
  } finally {
    saving.value = false
  }
}

async function openRecommendation(item: Recommendation) {
  void searchApi
    .recordSearchConversion({
      productId: item.productId,
      keyword: item.reason,
      source: 'recommendation',
    })
    .catch(() => undefined)
  await router.push(`/shop/${item.productId}`)
}

onMounted(() => {
  void loadRecommendations()
})
</script>

<template>
  <div class="route-view recommend-page">
    <PageHeader :title="$t('nav.recommend')" :eyebrow="$t('recommend.personalized')">
      <template #actions>
        <RouterLink class="secondary-button" to="/search">
          <el-icon><Search /></el-icon>
          <span>{{ $t('nav.search') }}</span>
        </RouterLink>
      </template>
    </PageHeader>

    <form class="profile-panel" @submit.prevent="saveProfile">
      <el-input
        v-model="form.interestProfile"
        :aria-label="$t('recommend.profilePlaceholder')"
        :placeholder="$t('recommend.profilePlaceholder')"
      />
      <el-input
        v-model="form.tags"
        :aria-label="$t('recommend.tagsPlaceholder')"
        :placeholder="$t('recommend.tagsPlaceholder')"
      />
      <el-button
        class="profile-panel__submit"
        type="primary"
        native-type="submit"
        :loading="saving"
        :disabled="saving"
        :icon="Star"
      >
        {{ $t('common.save') }}
      </el-button>
      <span v-if="profile" class="profile-panel__stored">
        {{ $t('recommend.storedAs', { profile }) }}
      </span>
      <p v-if="profileError" class="profile-panel__error" role="alert">
        {{ $t(profileError) }}
      </p>
    </form>

    <AsyncStateView
      :status="recommendationState.status.value"
      :error="recommendationState.error.value"
      :empty-title="$t('recommend.emptyTitle')"
      :empty-description="$t('recommend.emptyDescription')"
      @retry="loadRecommendations"
    >
      <template #error>
        <div class="recommend-error" role="alert">
          <Star class="empty-state-icon" aria-hidden="true" />
          <p>{{ $t('recommend.unableToLoad') }}</p>
          <el-button type="primary" @click="loadRecommendations">
            {{ $t('common.retry') }}
          </el-button>
        </div>
      </template>

      <template #empty>
        <div class="empty-state" role="status">
          <Star class="empty-state-icon" aria-hidden="true" />
          <h2>{{ $t('recommend.emptyTitle') }}</h2>
          <p>{{ $t('recommend.emptyDescription') }}</p>
        </div>
      </template>

      <div class="recommend-grid">
        <ProductCard
          v-for="entry in recommendationCards"
          :key="entry.source.productId"
          :product="entry.product"
          :primary-action-label="$t('common.open')"
          @primary="openRecommendation(entry.source)"
          @secondary="openRecommendation(entry.source)"
        >
          <template #badge>{{ entry.source.reason }}</template>
        </ProductCard>
      </div>
    </AsyncStateView>
  </div>
</template>

<style scoped>
.recommend-page {
  display: grid;
  gap: var(--space-5);
}

.profile-panel {
  display: grid;
  grid-template-columns: minmax(180px, 2fr) minmax(160px, 1fr) auto minmax(0, auto);
  gap: var(--space-3);
  align-items: center;
  min-width: 0;
  padding-block: var(--space-2);
}

.profile-panel__submit {
  min-width: 96px;
  min-height: 44px;
}

.profile-panel__stored {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--color-text-muted);
  font-weight: 700;
}

.profile-panel__error {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--color-danger);
}

.recommend-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--space-4);
}

.recommend-error {
  display: grid;
  justify-items: center;
  gap: var(--space-3);
  min-height: 280px;
  align-content: center;
  color: var(--color-text-muted);
  text-align: center;
}

@media (max-width: 900px) {
  .profile-panel {
    grid-template-columns: 1fr;
  }
}
</style>

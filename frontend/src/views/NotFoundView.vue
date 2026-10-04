<script setup lang="ts">
import { Back, House } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import MascotState from '@/components/mascot/MascotState.vue'

const { t } = useI18n()
const router = useRouter()

function goBack() {
  if (window.history.length > 1) {
    router.back()
    return
  }
  void router.push('/shop')
}
</script>

<template>
  <div class="route-view not-found-page" data-surface="route-recovery">
    <section class="not-found-band" aria-labelledby="not-found-title">
      <div class="not-found-visual">
        <strong aria-hidden="true">404</strong>
        <MascotState pose="warning" size="md" :alt="t('common.notFoundMascotAlt')" eager />
      </div>

      <div class="not-found-copy">
        <span>MonkeyShop / 404</span>
        <h1 id="not-found-title">{{ t('common.notFound') }}</h1>
        <p>{{ t('common.notFoundHint') }}</p>
        <div class="not-found-actions" data-surface="recovery-actions">
          <el-button :icon="Back" @click="goBack">{{ t('common.back') }}</el-button>
          <el-button type="primary" :icon="House" @click="router.push('/shop')">
            {{ t('nav.shop') }}
          </el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.not-found-page {
  display: grid;
  align-content: center;
  min-height: calc(100dvh - var(--consumer-header-height) - var(--space-8));
  min-width: 0;
}

.not-found-band {
  display: grid;
  grid-template-columns: minmax(280px, 0.8fr) minmax(0, 1.2fr);
  min-height: 480px;
  overflow: hidden;
  border: 1px solid var(--consumer-line);
  border-radius: var(--radius-surface);
  background: var(--consumer-surface-raised);
  box-shadow: var(--shadow-surface);
}

.not-found-visual,
.not-found-copy {
  display: grid;
  place-content: center;
}

.not-found-visual {
  justify-items: center;
  gap: var(--space-2);
  padding: var(--space-6);
  border-right: 1px solid var(--consumer-line-strong);
  background: var(--consumer-primary-soft);
}

.not-found-visual strong {
  color: var(--consumer-primary-strong);
  font-size: clamp(var(--text-3xl), 8vw, 4.5rem);
  line-height: 0.9;
}

.not-found-visual :deep(.mascot-state) {
  filter: drop-shadow(0 10px 16px color-mix(in srgb, var(--consumer-ink) 14%, transparent));
}

.not-found-copy {
  justify-items: start;
  gap: var(--space-3);
  padding: var(--space-8);
}

.not-found-copy > span {
  color: var(--consumer-primary-strong);
  font-size: var(--text-sm);
  font-weight: var(--font-weight-bold);
  text-transform: uppercase;
}

.not-found-copy h1,
.not-found-copy p {
  margin: 0;
}

.not-found-copy h1 {
  font-size: var(--text-3xl);
}

.not-found-copy p {
  max-width: 48ch;
  color: var(--consumer-muted);
  line-height: var(--leading-relaxed);
}

.not-found-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.not-found-actions :deep(.el-button) {
  min-height: var(--touch-target-min);
}

.not-found-actions :deep(.el-button:focus-visible) {
  outline: var(--focus-width) solid var(--consumer-primary);
  outline-offset: var(--focus-offset);
}

@media (max-width: 640px) {
  .not-found-page {
    min-height: auto;
  }

  .not-found-band {
    grid-template-columns: 1fr;
    min-height: 0;
  }

  .not-found-visual {
    padding: var(--space-5);
    border-right: 0;
    border-bottom: 1px solid var(--consumer-line-strong);
  }

  .not-found-visual strong {
    font-size: 48px;
  }

  .not-found-visual :deep(.mascot-state) {
    --mascot-size: 144px;
  }

  .not-found-copy {
    padding: var(--space-5);
  }

  .not-found-actions {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    width: 100%;
  }
}
</style>

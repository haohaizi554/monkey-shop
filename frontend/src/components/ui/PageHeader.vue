<script setup lang="ts">
import { useI18n } from 'vue-i18n'

defineProps<{
  title: string
  description?: string
  eyebrow?: string
}>()

const { t } = useI18n()
</script>

<template>
  <header class="page-header">
    <div class="page-header__main">
      <nav
        v-if="$slots.breadcrumbs"
        class="page-header__breadcrumbs"
        :aria-label="t('nav.breadcrumb')"
      >
        <slot name="breadcrumbs" />
      </nav>
      <p v-if="eyebrow" class="page-header__eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p v-if="description" class="page-header__description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

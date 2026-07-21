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
      <span class="page-header__palette" aria-hidden="true">
        <i />
        <i />
        <i />
        <i />
      </span>
      <p v-if="eyebrow" class="page-header__eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p v-if="description" class="page-header__description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions || $slots.visual" class="page-header__aside">
      <div v-if="$slots.actions" class="page-header__actions">
        <slot name="actions" />
      </div>
      <div v-if="$slots.visual" class="page-header__visual" aria-hidden="true">
        <slot name="visual" />
      </div>
    </div>
  </header>
</template>

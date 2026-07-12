<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ ariaLabel?: string }>()
const { t } = useI18n()
const accessibleLabel = computed(() => props.ariaLabel || t('common.pageControls'))
</script>

<template>
  <section class="admin-page-toolbar" :aria-label="accessibleLabel">
    <div v-if="$slots.search" class="admin-page-toolbar__search">
      <slot name="search" />
    </div>
    <div v-if="$slots.filters" class="admin-page-toolbar__filters">
      <slot name="filters" />
    </div>
    <div v-if="$slots.actions" class="admin-page-toolbar__actions">
      <slot name="actions" />
    </div>
  </section>
</template>

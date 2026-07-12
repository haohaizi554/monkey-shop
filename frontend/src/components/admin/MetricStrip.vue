<script setup lang="ts">
export interface MetricItem {
  key: string
  label: string
  value: string | number
  tone?: 'neutral' | 'info' | 'success' | 'warning' | 'danger'
}

defineProps<{ items: readonly MetricItem[] }>()
</script>

<template>
  <ul class="metric-strip">
    <li
      v-for="item in items"
      :key="item.key"
      class="metric-strip__item"
      :data-metric-key="item.key"
      :data-tone="item.tone || 'neutral'"
    >
      <span>{{ item.label }}</span>
      <strong>{{ item.value }}</strong>
    </li>
  </ul>
</template>

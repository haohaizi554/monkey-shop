<script setup lang="ts">
import welcome1x from '@/assets/mascot/monkey-welcome-1x.webp'

withDefaults(
  defineProps<{
    variant?: 'consumer' | 'admin'
  }>(),
  {
    variant: 'consumer',
  },
)
</script>

<template>
  <span class="brand-mascot" :data-variant="variant" aria-hidden="true">
    <img :src="welcome1x" alt="" width="56" height="56" loading="eager" decoding="async" />
  </span>
</template>

<style scoped>
.brand-mascot {
  position: relative;
  display: inline-block;
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--color-primary) 38%, var(--color-line));
  border-radius: var(--radius-circle);
  background: var(--color-primary-soft);
  box-shadow: inset 0 0 0 2px var(--color-surface);
}

.brand-mascot img {
  position: absolute;
  top: -3px;
  left: -10px;
  display: block;
  width: 56px;
  max-width: none;
  height: 56px;
  object-fit: contain;
}

.brand-mascot[data-variant='admin'] {
  width: 38px;
  height: 38px;
  border-color: var(--color-admin-chrome-accent);
  background: var(--color-admin-chrome-accent-soft);
  box-shadow: inset 0 0 0 2px var(--color-admin-chrome);
}

.brand-mascot[data-variant='admin'] img {
  top: -2px;
  left: -9px;
}
</style>

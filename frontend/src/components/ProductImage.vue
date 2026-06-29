<script setup lang="ts">
import { ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    src?: string
    alt: string
    fallback?: string
  }>(),
  {
    src: '',
    fallback: '/images/default_product.png',
  },
)

const currentSrc = ref(props.src || props.fallback)

watch(
  () => props.src,
  (value) => {
    currentSrc.value = value || props.fallback
  },
)
</script>

<template>
  <img
    class="product-image"
    :src="currentSrc"
    :alt="alt"
    loading="lazy"
    decoding="async"
    @error="currentSrc = fallback"
  >
</template>

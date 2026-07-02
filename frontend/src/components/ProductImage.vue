<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Directive } from 'vue'

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

function useFallbackImage(event: Event) {
  const image = event.currentTarget as HTMLImageElement
  const fallback = image.dataset.fallbackSrc
  if (fallback && image.getAttribute('src') !== fallback) {
    image.setAttribute('src', fallback)
  }
}

const vFallbackImg: Directive<HTMLImageElement, string> = {
  mounted(image, binding) {
    image.dataset.fallbackSrc = binding.value
    image.addEventListener('error', useFallbackImage)
  },
  updated(image, binding) {
    image.dataset.fallbackSrc = binding.value
  },
  beforeUnmount(image) {
    image.removeEventListener('error', useFallbackImage)
  },
}

watch(
  () => props.src,
  (value) => {
    currentSrc.value = value || props.fallback
  },
)
</script>

<template>
  <img
    v-fallback-img="fallback"
    class="product-image"
    :src="currentSrc"
    :alt="alt"
    loading="lazy"
    decoding="async"
  />
</template>

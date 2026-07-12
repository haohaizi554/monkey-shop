<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const builtInFallback = 'data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs='

const props = withDefaults(
  defineProps<{
    src?: string
    alt: string
    fallback?: string
  }>(),
  {
    src: '',
    fallback: '/images/default_product.jpg',
  },
)

const sourceFailed = ref(false)
const fallbackFailed = ref(false)
const resolvedSrc = computed(() => {
  if (!props.src || sourceFailed.value) {
    return props.fallback && !fallbackFailed.value ? props.fallback : builtInFallback
  }
  return props.src
})

function handleError() {
  if (resolvedSrc.value === builtInFallback) {
    return
  }
  if (props.src && !sourceFailed.value) {
    sourceFailed.value = true
    if (props.src === props.fallback) {
      fallbackFailed.value = true
    }
    return
  }
  fallbackFailed.value = true
}

watch(
  () => [props.src, props.fallback],
  () => {
    sourceFailed.value = false
    fallbackFailed.value = false
  },
)
</script>

<template>
  <img
    class="product-image"
    :src="resolvedSrc"
    :alt="alt"
    width="640"
    height="480"
    loading="lazy"
    decoding="async"
    @error="handleError"
  />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const builtInFallback = 'data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs='

const props = withDefaults(
  defineProps<{
    src?: string
    alt: string
    fallback?: string
    objectPosition?: string
  }>(),
  {
    src: '',
    fallback: '/images/default_product.jpg',
    objectPosition: 'center',
  },
)

const sourceFailed = ref(false)
const fallbackFailed = ref(false)
const loaded = ref(false)
const resolvedSrc = computed(() => {
  if (!props.src || sourceFailed.value) {
    return props.fallback && !fallbackFailed.value ? props.fallback : builtInFallback
  }
  return props.src
})
const imageState = computed(() => {
  if (!loaded.value) {
    return 'loading'
  }
  if (!props.src || sourceFailed.value) {
    return 'fallback'
  }
  return 'loaded'
})

function handleError() {
  loaded.value = false
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

function handleLoad() {
  loaded.value = true
}

watch(
  () => [props.src, props.fallback],
  () => {
    sourceFailed.value = false
    fallbackFailed.value = false
    loaded.value = false
  },
)
</script>

<template>
  <img
    class="product-image"
    :src="resolvedSrc"
    :alt="alt"
    :style="{ objectPosition: props.objectPosition }"
    :data-image-state="imageState"
    width="640"
    height="480"
    loading="lazy"
    decoding="async"
    @error="handleError"
    @load="handleLoad"
  />
</template>

<style scoped>
.product-image {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  background: var(--color-surface-subtle);
}

.product-image[data-image-state='loading'] {
  background: var(--color-surface-subtle);
}

.product-image[data-image-state='fallback'] {
  object-fit: contain;
  padding: var(--space-4);
}
</style>

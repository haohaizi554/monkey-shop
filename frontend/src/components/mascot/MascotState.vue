<script setup lang="ts">
import { computed } from 'vue'
import welcome1x from '@/assets/mascot/monkey-welcome-1x.webp'
import welcome2x from '@/assets/mascot/monkey-welcome-2x.webp'
import shoppingBag1x from '@/assets/mascot/monkey-shopping-bag-1x.webp'
import search1x from '@/assets/mascot/monkey-search-1x.webp'
import cart1x from '@/assets/mascot/monkey-cart-1x.webp'
import package1x from '@/assets/mascot/monkey-package-1x.webp'
import celebrate1x from '@/assets/mascot/monkey-celebrate-1x.webp'
import clipboard1x from '@/assets/mascot/monkey-clipboard-1x.webp'
import warning1x from '@/assets/mascot/monkey-warning-1x.webp'
import shield1x from '@/assets/mascot/monkey-shield-1x.webp'
import support1x from '@/assets/mascot/monkey-support-1x.webp'
import dashboard1x from '@/assets/mascot/monkey-dashboard-1x.webp'
import hourglass1x from '@/assets/mascot/monkey-hourglass-1x.webp'

export type MascotPose =
  | 'welcome'
  | 'shoppingBag'
  | 'search'
  | 'cart'
  | 'package'
  | 'celebrate'
  | 'clipboard'
  | 'warning'
  | 'shield'
  | 'support'
  | 'dashboard'
  | 'hourglass'

type MascotSize = 'sm' | 'md' | 'lg'

const props = withDefaults(
  defineProps<{
    pose?: MascotPose
    size?: MascotSize
    decorative?: boolean
    alt?: string
    eager?: boolean
  }>(),
  {
    pose: 'welcome',
    size: 'md',
    decorative: false,
    alt: '',
    eager: false,
  },
)

const poseSources: Readonly<Record<MascotPose, string>> = Object.freeze({
  welcome: welcome1x,
  shoppingBag: shoppingBag1x,
  search: search1x,
  cart: cart1x,
  package: package1x,
  celebrate: celebrate1x,
  clipboard: clipboard1x,
  warning: warning1x,
  shield: shield1x,
  support: support1x,
  dashboard: dashboard1x,
  hourglass: hourglass1x,
})

const dimensions: Readonly<Record<MascotSize, number>> = Object.freeze({
  sm: 128,
  md: 192,
  lg: 288,
})
const validPoses = new Set<MascotPose>(Object.keys(poseSources) as MascotPose[])
const validSizes = new Set<MascotSize>(Object.keys(dimensions) as MascotSize[])

const resolvedPose = computed<MascotPose>(() =>
  validPoses.has(props.pose as MascotPose) ? (props.pose as MascotPose) : 'welcome',
)
const resolvedSize = computed<MascotSize>(() =>
  validSizes.has(props.size as MascotSize) ? (props.size as MascotSize) : 'md',
)
const source = computed(() => poseSources[resolvedPose.value])
const dimension = computed(() => dimensions[resolvedSize.value])
const sourceSet = computed(() =>
  resolvedPose.value === 'welcome' ? `${welcome1x} 1x, ${welcome2x} 2x` : undefined,
)
const alternativeText = computed(() => (props.decorative ? '' : props.alt))
</script>

<template>
  <img
    class="mascot-state"
    :data-pose="resolvedPose"
    :data-size="resolvedSize"
    :src="source"
    :srcset="sourceSet"
    :width="dimension"
    :height="dimension"
    :alt="alternativeText"
    :aria-hidden="decorative ? 'true' : undefined"
    :loading="eager ? 'eager' : 'lazy'"
    decoding="async"
  />
</template>

<style scoped>
.mascot-state {
  display: block;
  width: min(100%, var(--mascot-size, 192px));
  height: auto;
  aspect-ratio: 1;
  object-fit: contain;
  transform-origin: bottom center;
  animation: mascot-arrive var(--motion-structure) var(--easing-emphasized) both;
}

.mascot-state[data-size='sm'] {
  --mascot-size: 128px;
}

.mascot-state[data-size='md'] {
  --mascot-size: 192px;
}

.mascot-state[data-size='lg'] {
  --mascot-size: 288px;
}

@keyframes mascot-arrive {
  from {
    opacity: 0;
    transform: translateY(6px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>

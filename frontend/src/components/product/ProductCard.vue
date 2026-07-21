<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import ProductImage from '@/components/ProductImage.vue'
import type { Monkey } from '@/types'
import { money } from '@/utils/format'

interface ProductCardProps {
  product: Monkey
  pending?: boolean
  primaryActionLabel: string
  disabled?: boolean
}

const props = withDefaults(defineProps<ProductCardProps>(), {
  pending: false,
  disabled: false,
})
const emit = defineEmits<{
  primary: []
  secondary: []
}>()
const { t } = useI18n()

const hasPrice = computed(() => Number.isFinite(Number(props.product.price)))
const hasStock = computed(() => Number.isFinite(props.product.stock))
const soldOut = computed(() => hasStock.value && props.product.stock <= 0)
const actionDisabled = computed(() => props.disabled || props.pending || soldOut.value)
</script>

<template>
  <article class="product-card">
    <button
      class="product-card__media"
      type="button"
      :aria-label="product.name"
      @click="emit('secondary')"
    >
      <ProductImage :src="product.imageUrl" :alt="product.name" />
      <span v-if="$slots.badge" class="product-card__badge">
        <slot name="badge" />
      </span>
    </button>

    <div class="product-card__body product-body">
      <div class="product-card__heading">
        <div class="product-card__identity">
          <button class="product-card__title" type="button" @click="emit('secondary')">
            <h2>{{ product.name }}</h2>
          </button>
          <p v-if="product.breed" class="product-card__breed">{{ product.breed }}</p>
        </div>
        <strong v-if="hasPrice" class="product-card__price">{{ money(product.price) }}</strong>
      </div>

      <p v-if="product.description" class="product-card__description description">
        {{ product.description }}
      </p>

      <div class="product-card__actions product-actions">
        <span v-if="hasStock" class="stock-pill" :class="{ 'stock-pill-muted': soldOut }">
          {{ t('common.stock') }} {{ product.stock }}
        </span>
        <el-button
          class="product-card__primary"
          type="primary"
          :loading="pending"
          :disabled="actionDisabled"
          @click="emit('primary')"
        >
          {{ primaryActionLabel }}
        </el-button>
      </div>
    </div>
  </article>
</template>

<style scoped>
.product-card {
  --product-accent: var(--color-primary);

  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
  transition:
    border-color var(--motion-fast),
    box-shadow var(--motion-fast),
    transform var(--motion-fast);
}

.product-card:nth-child(4n + 2) {
  --product-accent: var(--color-cobalt);
}

.product-card:nth-child(4n + 3) {
  --product-accent: var(--color-coral);
}

.product-card:nth-child(4n + 4) {
  --product-accent: var(--color-honey);
}

.product-card:hover,
.product-card:focus-within {
  border-color: color-mix(in srgb, var(--product-accent) 55%, var(--color-line));
  box-shadow: var(--shadow-control);
  transform: translateY(-2px);
}

.product-card__media {
  position: relative;
  display: block;
  width: 100%;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  border: 0;
  padding: 0;
  background: var(--color-surface-subtle);
  cursor: pointer;
}

.product-card__media :deep(.product-image) {
  width: 100%;
  height: 100%;
  aspect-ratio: auto;
  object-fit: cover;
  transition: transform var(--motion-structure);
}

.product-card__media:hover :deep(.product-image) {
  transform: scale(1.02);
}

.product-card__badge {
  position: absolute;
  top: var(--space-3);
  left: var(--space-3);
  max-width: calc(100% - var(--space-6));
  overflow: hidden;
  border-radius: var(--radius-pill);
  padding: var(--space-1) var(--space-2);
  color: var(--color-text);
  background: color-mix(in srgb, var(--color-surface) 90%, transparent);
  box-shadow: var(--shadow-control);
  font-size: var(--text-xs);
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-card__body {
  grid-template-rows: auto minmax(0, 1fr) auto;
  min-width: 0;
  border-top: 3px solid var(--product-accent);
}

.product-card__heading {
  display: flex;
  gap: var(--space-3);
  align-items: flex-start;
  justify-content: space-between;
  min-width: 0;
}

.product-card__identity {
  min-width: 0;
}

.product-card__title {
  max-width: 100%;
  border: 0;
  padding: 0;
  color: var(--color-text);
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.product-card__title h2 {
  margin: 0;
  overflow-wrap: anywhere;
  font-size: var(--text-xl);
  line-height: var(--leading-tight);
}

.product-card__breed,
.product-card__description {
  color: var(--color-text-muted);
}

.product-card__breed {
  margin: var(--space-1) 0 0;
  line-height: var(--leading-normal);
}

.product-card__price {
  flex: 0 0 auto;
  color: var(--color-honey);
  white-space: nowrap;
}

.product-card__description {
  display: -webkit-box;
  min-height: calc(2 * var(--text-base) * var(--leading-normal));
  margin: 0;
  overflow: hidden;
  line-height: var(--leading-normal);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.product-card__actions {
  align-self: end;
  min-width: 0;
}

.product-card__primary {
  min-width: 112px;
  min-height: 44px;
  margin-left: auto;
}

@media (max-width: 520px) {
  .product-card__heading,
  .product-card__actions {
    align-items: stretch;
    flex-direction: column;
  }

  .product-card__primary {
    width: 100%;
    margin-left: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .product-card,
  .product-card__media :deep(.product-image) {
    transition: none;
  }

  .product-card:hover,
  .product-card:focus-within {
    transform: none;
  }
}
</style>

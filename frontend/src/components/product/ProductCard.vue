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
const coat = computed(() => readAttribute(['coat', 'furColor', 'colour', 'color']))
const warehouse = computed(() =>
  readAttribute(['warehouse', 'warehouseCode', 'warehouseName', 'province']),
)

function readAttribute(keys: string[]): string | undefined {
  for (const key of keys) {
    const value = props.product.attributes?.[key]
    if (typeof value === 'string' && value.trim()) {
      return value.trim()
    }
    if (typeof value === 'number' && Number.isFinite(value)) {
      return String(value)
    }
  }
  return undefined
}
</script>

<template>
  <article
    class="product-card"
    data-surface="specimen"
    :data-state="soldOut ? 'sold-out' : pending ? 'pending' : 'available'"
    :aria-busy="pending"
  >
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

      <dl v-if="hasStock || coat || warehouse" class="product-card__specs">
        <div v-if="coat" data-spec="coat">
          <dt>{{ t('search.attribute') }}</dt>
          <dd>{{ coat }}</dd>
        </div>
        <div v-if="hasStock" data-spec="stock">
          <dt>{{ t('common.stock') }}</dt>
          <dd>{{ product.stock }}</dd>
        </div>
        <div v-if="warehouse" data-spec="warehouse">
          <dt>{{ t('inventory.warehouse') }}</dt>
          <dd>{{ warehouse }}</dd>
        </div>
      </dl>

      <div class="product-card__actions product-actions">
        <span
          v-if="hasStock"
          class="stock-pill"
          :class="{ 'stock-pill-muted': soldOut }"
          :data-state="soldOut ? 'sold-out' : 'available'"
        >
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
  position: relative;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  background: var(--color-surface);
  box-shadow: var(--shadow-surface);
  transition:
    border-color var(--motion-fast),
    box-shadow var(--motion-fast),
    transform var(--motion-fast);
}

.product-card:hover,
.product-card:focus-within {
  border-color: var(--color-primary);
  box-shadow: var(--shadow-control);
  transform: translateY(-2px);
}

.product-card:active {
  transform: translateY(1px);
}

.product-card[data-state='sold-out'] .product-card__media :deep(.product-image) {
  filter: grayscale(0.88);
}

.product-card[data-state='pending'] {
  cursor: progress;
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
  color: var(--color-ink);
  background: var(--color-surface);
  box-shadow: var(--shadow-control);
  font-size: var(--text-xs);
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-card__body {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto auto;
  gap: var(--space-4);
  min-width: 0;
  padding: var(--space-5);
}

.product-card__heading {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-3);
  align-items: flex-start;
  min-width: 0;
}

.product-card__identity {
  min-width: 0;
}

.product-card__title {
  display: block;
  max-width: 100%;
  border: 0;
  padding: 0;
  color: var(--color-ink);
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
  color: var(--color-muted);
}

.product-card__breed {
  margin: var(--space-1) 0 0;
  line-height: var(--leading-normal);
}

.product-card__price {
  flex: 0 0 auto;
  color: color-mix(in srgb, var(--color-accent) 72%, var(--color-ink));
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

.product-card__specs {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(88px, 1fr));
  gap: var(--space-3);
  min-width: 0;
  margin: 0;
  border-top: 1px solid var(--color-line);
  padding-top: var(--space-3);
}

.product-card__specs > div {
  min-width: 0;
}

.product-card__specs dt {
  overflow-wrap: anywhere;
  color: var(--color-muted);
  font-size: var(--text-xs);
  font-weight: var(--font-weight-semibold);
  text-transform: uppercase;
}

.product-card__specs dd {
  margin: var(--space-1) 0 0;
  overflow-wrap: anywhere;
  color: var(--color-ink);
  font-size: var(--text-sm);
  font-weight: var(--font-weight-bold);
}

.product-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  align-items: center;
  align-self: end;
  min-width: 0;
}

.product-card__primary {
  min-width: 112px;
  min-height: var(--touch-target-min);
  margin-left: auto;
}

@media (max-width: 520px) {
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

  .product-card:active {
    transform: none;
  }
}
</style>

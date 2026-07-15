<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(
  defineProps<{
    title: string
    description?: string
    disabled?: boolean
  }>(),
  {
    description: undefined,
    disabled: false,
  },
)

const sectionId = useId()
const descriptionId = computed(() => (props.description ? `${sectionId}-description` : undefined))
</script>

<template>
  <fieldset class="form-section" :disabled="disabled" :aria-describedby="descriptionId">
    <legend class="form-section__legend">{{ title }}</legend>
    <p v-if="description" :id="descriptionId" class="form-section__description">
      {{ description }}
    </p>
    <div class="form-section__body">
      <slot />
    </div>
  </fieldset>
</template>

<style scoped>
.form-section {
  min-width: 0;
  margin: 0;
  padding: var(--space-5) 0 0;
  border: 0;
  border-top: 1px solid var(--color-line);
}

.form-section__legend {
  max-width: 100%;
  padding: 0;
  color: var(--color-ink);
  font-size: var(--text-lg);
  font-weight: 700;
  line-height: var(--leading-tight);
  overflow-wrap: anywhere;
}

.form-section__description {
  max-width: 64ch;
  margin: var(--space-2) 0 0;
  color: var(--color-muted);
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
}

.form-section__body {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
  margin-top: var(--space-4);
}
</style>

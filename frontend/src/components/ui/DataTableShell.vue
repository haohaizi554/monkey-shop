<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = withDefaults(
  defineProps<{
    ariaLabel?: string
    empty?: boolean
    busy?: boolean
  }>(),
  {
    ariaLabel: undefined,
    empty: false,
    busy: false,
  },
)

const { t } = useI18n()
const accessibleLabel = computed(() => props.ariaLabel || t('common.dataTable'))
</script>

<template>
  <section
    class="data-table-shell"
    :aria-label="empty ? accessibleLabel : undefined"
    :aria-busy="busy || undefined"
  >
    <div v-if="$slots.toolbar" class="data-table-shell__toolbar">
      <slot name="toolbar" />
    </div>
    <div v-if="empty" class="data-table-shell__empty" role="status">
      <slot name="empty" />
    </div>
    <div
      v-else
      class="data-table-shell__scroller"
      role="region"
      tabindex="0"
      :aria-label="accessibleLabel"
    >
      <slot />
    </div>
    <footer v-if="$slots.footer" class="data-table-shell__footer">
      <slot name="footer" />
    </footer>
  </section>
</template>

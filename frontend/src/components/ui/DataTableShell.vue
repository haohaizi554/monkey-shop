<script setup lang="ts">
withDefaults(
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
</script>

<template>
  <section
    class="data-table-shell"
    :aria-label="ariaLabel || undefined"
    :aria-busy="busy || undefined"
  >
    <div v-if="$slots.toolbar" class="data-table-shell__toolbar">
      <slot name="toolbar" />
    </div>
    <div v-if="empty" class="data-table-shell__empty" role="status">
      <slot name="empty" />
    </div>
    <div v-else class="data-table-shell__scroller">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="data-table-shell__footer">
      <slot name="footer" />
    </footer>
  </section>
</template>

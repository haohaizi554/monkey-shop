<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useRoute } from 'vue-router'
import AdminSidebar from '@/components/shell/AdminSidebar.vue'
import AdminTopbar from '@/components/shell/AdminTopbar.vue'
import ConsumerBottomNav from '@/components/shell/ConsumerBottomNav.vue'
import ConsumerHeader from '@/components/shell/ConsumerHeader.vue'
import type { RouteArea } from '@/router/route-meta'

const route = useRoute()
const area = computed<RouteArea>(() => route.meta.area || 'consumer')
const adminNavigationOpen = ref(false)
const adminTopbar = ref<InstanceType<typeof AdminTopbar>>()

async function closeAdminNavigation() {
  adminNavigationOpen.value = false
  await nextTick()
  adminTopbar.value?.focusNavigationTrigger()
}
</script>

<template>
  <div class="app-shell" :data-area="area">
    <ConsumerHeader v-if="area !== 'admin'" :compact="area === 'auth'" />
    <AdminSidebar v-else :open="adminNavigationOpen" @close="closeAdminNavigation" />
    <AdminTopbar
      v-if="area === 'admin'"
      ref="adminTopbar"
      :navigation-open="adminNavigationOpen"
      @toggle-navigation="adminNavigationOpen = !adminNavigationOpen"
    />
    <main class="app-main" tabindex="-1">
      <slot />
    </main>
    <ConsumerBottomNav v-if="area === 'consumer'" />
  </div>
</template>

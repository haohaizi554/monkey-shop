<script setup lang="ts">
import en from 'element-plus/es/locale/lang/en'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import AppErrorBoundary from '@/components/AppErrorBoundary.vue'
import AppShell from '@/components/AppShell.vue'
import AppFeedbackHost from '@/components/feedback/AppFeedbackHost.vue'

const route = useRoute()
const { locale, t } = useI18n()
const elementLocale = computed(() => (locale.value === 'zh' ? zhCn : en))
const messageConfig = {
  duration: 4200,
  grouping: true,
  max: 2,
  offset: 18,
  showClose: true,
}

watch(
  [locale, () => route.meta.titleKey],
  ([language, titleKey]) => {
    if (typeof document === 'undefined') {
      return
    }
    document.documentElement.lang = language
    document.title = `${t(typeof titleKey === 'string' ? titleKey : 'nav.shop')} | MonkeyShop`
  },
  { immediate: true, flush: 'post' },
)
</script>

<template>
  <el-config-provider :locale="elementLocale" :message="messageConfig">
    <AppShell>
      <AppErrorBoundary>
        <RouterView v-slot="{ Component }">
          <Transition name="route" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </AppErrorBoundary>
    </AppShell>
    <AppFeedbackHost />
  </el-config-provider>
</template>

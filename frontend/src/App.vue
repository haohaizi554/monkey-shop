<script setup lang="ts">
import en from 'element-plus/es/locale/lang/en'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import AppFeedbackHost from '@/components/feedback/AppFeedbackHost.vue'

const { locale } = useI18n()
const elementLocale = computed(() => (locale.value === 'zh' ? zhCn : en))
const messageConfig = {
  duration: 4200,
  grouping: true,
  max: 2,
  offset: 18,
  showClose: true,
}
</script>

<template>
  <el-config-provider :locale="elementLocale" :message="messageConfig">
    <RouterView v-slot="{ Component }">
      <Transition name="route" mode="out-in">
        <component :is="Component" />
      </Transition>
    </RouterView>
    <AppFeedbackHost />
  </el-config-provider>
</template>

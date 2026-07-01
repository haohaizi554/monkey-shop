import 'element-plus/theme-chalk/dark/css-vars.css'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import { i18n } from './locales'
import { router } from './router'
import './styles/main.css'

createApp(App).use(createPinia()).use(router).use(i18n).mount('#app')

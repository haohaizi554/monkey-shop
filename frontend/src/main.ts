import '@fontsource-variable/noto-sans-sc/index.css'
import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import { installTracking } from './TrackingSdk'
import { i18n } from './locales'
import { router } from './router'
import { reportUiError } from './utils/reportUiError'
import './styles/main.css'

const app = createApp(App).use(createPinia()).use(router).use(i18n)

app.config.errorHandler = (error, _instance, info) => {
  reportUiError(error, info)
}

installTracking(router)

app.mount('#app')

<script setup lang="ts">
import { Refresh, Upload } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import * as authApi from '@/api/auth'
import AppShell from '@/components/AppShell.vue'
import HumanVerification from '@/components/HumanVerification.vue'
import { useAuthStore } from '@/stores/auth'
import type { CaptchaConfig } from '@/types'

const router = useRouter()
const auth = useAuthStore()
const { t } = useI18n()
const heroImage = '/images/monkey.png'
const activeTab = ref<'login' | 'register' | 'reset'>('login')
const loginCaptchaUrl = ref(authApi.captchaUrl('auth'))
const registerCaptchaUrl = ref(authApi.captchaUrl('auth'))
const showAdminMfa = ref(false)
const showLoginCaptcha = ref(false)
const avatarFile = ref<File | null>(null)
const avatarPreview = ref('')
const submitting = ref(false)
const captchaConfig = ref<CaptchaConfig>({ provider: 'local', siteKey: '' })
const turnstileEnabled = computed(() => captchaConfig.value.provider === 'turnstile')

const loginForm = reactive({ username: '', password: '', captcha: '', totp: '' })
const registerForm = reactive({ username: '', password: '', phone: '', email: '', captcha: '' })
const resetRequestCaptcha = ref('')
const resetForm = reactive({
  username: '',
  phone: '',
  email: '',
  otp: '',
  emailToken: '',
  newPassword: '',
  captcha: '',
})

async function loadCaptchaConfig() {
  try {
    captchaConfig.value = await authApi.captchaConfig()
    showLoginCaptcha.value = turnstileEnabled.value
  } catch {
    captchaConfig.value = { provider: 'local', siteKey: '' }
  }
}

function refreshCaptcha(scope: 'login' | 'register') {
  if (scope === 'login') {
    loginCaptchaUrl.value = authApi.captchaUrl('auth')
  } else {
    registerCaptchaUrl.value = authApi.captchaUrl('auth')
  }
}

function selectAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  avatarFile.value = file || null
  avatarPreview.value = file ? URL.createObjectURL(file) : ''
}

function loginErrorMessage(message: string): string {
  if (message === 'admin mfa required') {
    return t('auth.adminMfaRequired')
  }
  if (message === 'admin mfa invalid') {
    return t('auth.adminMfaInvalid')
  }
  if (message === 'captcha required') {
    return t('auth.captchaRequired')
  }
  if (message === 'captcha incorrect') {
    return t('auth.captchaIncorrect')
  }
  return message
}

async function submitLogin() {
  if (turnstileEnabled.value && !loginForm.captcha) {
    ElMessage.error(t('auth.captchaRequired'))
    return
  }
  submitting.value = true
  try {
    await auth.login(loginForm)
    ElMessage.success(t('auth.signedIn'))
  } catch (error) {
    const message = error instanceof Error ? error.message : t('auth.signInFailed')
    showAdminMfa.value = message === 'admin mfa required' || message === 'admin mfa invalid'
    showLoginCaptcha.value =
      turnstileEnabled.value || message === 'captcha required' || message === 'captcha incorrect'
    if (showLoginCaptcha.value && !turnstileEnabled.value) {
      refreshCaptcha('login')
    }
    ElMessage.error(loginErrorMessage(message))
  } finally {
    submitting.value = false
  }
}

async function submitRegister() {
  if (turnstileEnabled.value && !registerForm.captcha) {
    ElMessage.error(t('auth.captchaRequired'))
    return
  }
  submitting.value = true
  try {
    await auth.register({ ...registerForm, avatarFile: avatarFile.value })
    ElMessage.success(t('auth.registrationComplete'))
    activeTab.value = 'login'
    loginForm.username = registerForm.username
  } catch (error) {
    if (!turnstileEnabled.value) {
      refreshCaptcha('register')
    }
    ElMessage.error(error instanceof Error ? error.message : t('auth.registrationFailed'))
  } finally {
    submitting.value = false
  }
}

async function requestResetCode() {
  if (turnstileEnabled.value && !resetRequestCaptcha.value) {
    ElMessage.error(t('auth.captchaRequired'))
    return
  }
  try {
    await authApi.requestPasswordReset({ ...resetForm, captcha: resetRequestCaptcha.value })
    ElMessage.success(t('auth.resetChallengeSent'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('auth.requestFailed'))
  }
}

async function submitReset() {
  if (turnstileEnabled.value && !resetForm.captcha) {
    ElMessage.error(t('auth.captchaRequired'))
    return
  }
  submitting.value = true
  try {
    await authApi.resetPassword(resetForm)
    ElMessage.success(t('auth.passwordResetComplete'))
    activeTab.value = 'login'
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('auth.passwordResetFailed'))
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadCaptchaConfig()
})
</script>

<template>
  <AppShell>
    <section class="auth-layout">
      <div class="auth-visual">
        <img :src="heroImage" :alt="$t('auth.storefrontAlt')" />
      </div>

      <el-tabs v-model="activeTab" class="auth-panel" stretch>
        <el-tab-pane name="login" :label="$t('auth.login')">
          <el-form label-position="top" @submit.prevent="submitLogin">
            <el-form-item :label="$t('auth.username')">
              <el-input v-model="loginForm.username" autocomplete="username" />
            </el-form-item>
            <el-form-item :label="$t('auth.password')">
              <el-input
                v-model="loginForm.password"
                type="password"
                autocomplete="current-password"
                show-password
              />
            </el-form-item>
            <el-form-item v-if="showAdminMfa" :label="$t('auth.totp')">
              <el-input v-model="loginForm.totp" inputmode="numeric" />
            </el-form-item>
            <el-form-item v-if="showLoginCaptcha || turnstileEnabled" :label="$t('auth.captcha')">
              <HumanVerification
                v-if="turnstileEnabled"
                v-model="loginForm.captcha"
                action="login"
                :site-key="captchaConfig.siteKey"
              />
              <div class="captcha-row">
                <template v-if="!turnstileEnabled">
                  <el-input v-model="loginForm.captcha" />
                  <button
                    class="captcha-image-button"
                    type="button"
                    :aria-label="$t('common.refreshCaptcha')"
                    @click="refreshCaptcha('login')"
                  >
                    <img :src="loginCaptchaUrl" alt="Captcha" />
                  </button>
                  <el-button :icon="Refresh" circle @click="refreshCaptcha('login')" />
                </template>
              </div>
            </el-form-item>
            <el-button type="primary" native-type="submit" :loading="submitting" class="full-width">
              {{ $t('auth.login') }}
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane name="register" :label="$t('auth.register')">
          <el-form label-position="top" @submit.prevent="submitRegister">
            <el-form-item :label="$t('auth.username')">
              <el-input v-model="registerForm.username" autocomplete="username" />
            </el-form-item>
            <el-form-item :label="$t('auth.password')">
              <el-input
                v-model="registerForm.password"
                type="password"
                autocomplete="new-password"
                show-password
              />
            </el-form-item>
            <el-form-item :label="$t('auth.phone')">
              <el-input v-model="registerForm.phone" />
            </el-form-item>
            <el-form-item :label="$t('auth.email')">
              <el-input v-model="registerForm.email" />
            </el-form-item>
            <el-form-item :label="$t('auth.avatar')">
              <label class="file-picker" for="register-avatar-input">
                <el-icon><Upload /></el-icon>
                <span>{{ avatarFile?.name || $t('common.upload') }}</span>
                <input
                  id="register-avatar-input"
                  type="file"
                  accept="image/png,image/jpeg"
                  @change="selectAvatar"
                />
              </label>
              <img
                v-if="avatarPreview"
                class="avatar-preview"
                :src="avatarPreview"
                :alt="$t('auth.avatarPreview')"
              />
            </el-form-item>
            <el-form-item :label="$t('auth.captcha')">
              <HumanVerification
                v-if="turnstileEnabled"
                v-model="registerForm.captcha"
                action="register"
                :site-key="captchaConfig.siteKey"
              />
              <div class="captcha-row">
                <template v-if="!turnstileEnabled">
                  <el-input v-model="registerForm.captcha" />
                  <button
                    class="captcha-image-button"
                    type="button"
                    :aria-label="$t('common.refreshCaptcha')"
                    @click="refreshCaptcha('register')"
                  >
                    <img :src="registerCaptchaUrl" alt="Captcha" />
                  </button>
                  <el-button :icon="Refresh" circle @click="refreshCaptcha('register')" />
                </template>
              </div>
            </el-form-item>
            <el-button type="primary" native-type="submit" :loading="submitting" class="full-width">
              {{ $t('auth.register') }}
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane name="reset" :label="$t('auth.reset')">
          <el-form label-position="top" @submit.prevent="submitReset">
            <el-form-item :label="$t('auth.username')">
              <el-input v-model="resetForm.username" />
            </el-form-item>
            <el-form-item :label="$t('auth.phone')">
              <el-input v-model="resetForm.phone" />
            </el-form-item>
            <el-form-item :label="$t('auth.email')">
              <el-input v-model="resetForm.email" />
            </el-form-item>
            <el-form-item v-if="turnstileEnabled" :label="$t('auth.captcha')">
              <HumanVerification
                v-model="resetRequestCaptcha"
                action="password-reset-request"
                :site-key="captchaConfig.siteKey"
              />
            </el-form-item>
            <el-button plain class="full-width" @click="requestResetCode">
              {{ $t('auth.requestResetCode') }}
            </el-button>
            <el-form-item :label="$t('auth.otp')">
              <el-input v-model="resetForm.otp" />
            </el-form-item>
            <el-form-item :label="$t('auth.emailToken')">
              <el-input v-model="resetForm.emailToken" />
            </el-form-item>
            <el-form-item :label="$t('auth.newPassword')">
              <el-input v-model="resetForm.newPassword" type="password" show-password />
            </el-form-item>
            <el-form-item v-if="turnstileEnabled" :label="$t('auth.captcha')">
              <HumanVerification
                v-model="resetForm.captcha"
                action="password-reset"
                :site-key="captchaConfig.siteKey"
              />
            </el-form-item>
            <el-button type="primary" native-type="submit" :loading="submitting" class="full-width">
              {{ $t('auth.reset') }}
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <el-button text @click="router.push('/shop')"> {{ $t('auth.continueBrowsing') }} </el-button>
    </section>
  </AppShell>
</template>

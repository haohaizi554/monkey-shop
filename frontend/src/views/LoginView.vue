<script setup lang="ts">
import {
  CircleCheckFilled,
  CloseBold,
  InfoFilled,
  Back,
  Upload,
  WarningFilled,
} from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import * as authApi from '@/api/auth'
import { ApiError } from '@/api/http'
import monkeyLoginImage from '@/assets/monkey-login.webp'
import HumanVerification from '@/components/HumanVerification.vue'
import { useAuthStore } from '@/stores/auth'
import type { CaptchaConfig } from '@/types'

const auth = useAuthStore()
const { t } = useI18n()
const activeTab = ref<'login' | 'register' | 'reset'>('login')
const loginCaptchaUrl = ref(authApi.captchaUrl('auth'))
const registerCaptchaUrl = ref(authApi.captchaUrl('auth'))
const showAdminMfa = ref(false)
const showLoginCaptcha = ref(false)
const avatarFile = ref<File | null>(null)
const avatarPreview = ref('')
const submitting = ref(false)
const resetRequestPending = ref(false)
const captchaConfig = ref<CaptchaConfig>({ provider: 'local', siteKey: '' })
const turnstileEnabled = computed(() => captchaConfig.value.provider === 'turnstile')

const loginForm = reactive({ username: '', password: '', captcha: '', totp: '' })
const registerForm = reactive({ username: '', password: '', phone: '', email: '', captcha: '' })
const loginFormRef = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()
const resetFormRef = ref<FormInstance>()

const loginRules = computed<FormRules>(() => ({
  username: [{ required: true, message: t('auth.usernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('auth.passwordRequired'), trigger: 'blur' }],
}))
const registerRules = computed<FormRules>(() => ({
  username: [
    { required: true, message: t('auth.usernameRequired'), trigger: 'blur' },
    { min: 3, max: 32, message: t('auth.usernameLength'), trigger: 'blur' },
  ],
  password: [
    { required: true, message: t('auth.passwordRequired'), trigger: 'blur' },
    { min: 8, message: t('auth.passwordMinLength'), trigger: 'blur' },
  ],
  phone: [
    { required: true, message: t('auth.phoneRequired'), trigger: 'blur' },
    { pattern: /^1\d{6,14}$/, message: t('auth.phoneInvalid'), trigger: 'blur' },
  ],
  email: [{ type: 'email', message: t('auth.emailInvalid'), trigger: 'blur' }],
}))
const resetRules = computed<FormRules>(() => ({
  username: [{ required: true, message: t('auth.usernameRequired'), trigger: 'blur' }],
  phone: [
    { required: true, message: t('auth.phoneRequired'), trigger: 'blur' },
    { pattern: /^1\d{6,14}$/, message: t('auth.phoneInvalid'), trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: t('auth.passwordRequired'), trigger: 'blur' },
    { min: 8, message: t('auth.passwordMinLength'), trigger: 'blur' },
  ],
}))
const resetRequestCaptcha = ref('')
type PasswordResetStage = 'identity' | 'challenge'
const resetStage = ref<PasswordResetStage>('identity')
const resetForm = reactive({
  username: '',
  phone: '',
  email: '',
  otp: '',
  emailToken: '',
  newPassword: '',
  captcha: '',
})
type AuthNoticeLevel = 'error' | 'warning' | 'success' | 'info'
const authNotice = ref<{ level: AuthNoticeLevel; message: string } | null>(null)
const authNoticeRole = computed(() =>
  authNotice.value?.level === 'error' || authNotice.value?.level === 'warning' ? 'alert' : 'status',
)

const noticeIcon = computed(() => {
  if (authNotice.value?.level === 'success') {
    return CircleCheckFilled
  }
  if (authNotice.value?.level === 'info') {
    return InfoFilled
  }
  return WarningFilled
})

function showAuthNotice(level: AuthNoticeLevel, message: string) {
  authNotice.value = { level, message }
}

function clearAuthNotice() {
  authNotice.value = null
}

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
  if (avatarPreview.value) {
    URL.revokeObjectURL(avatarPreview.value)
  }
  avatarFile.value = file || null
  avatarPreview.value = file ? URL.createObjectURL(file) : ''
}

function authChallenge(message: string): string | null {
  const normalized = message.trim().toLowerCase()
  return [
    'admin mfa required',
    'admin mfa invalid',
    'captcha required',
    'captcha incorrect',
  ].includes(normalized)
    ? normalized
    : null
}

function authErrorMessage(error: unknown, fallbackKey: string): string {
  const message = error instanceof Error ? error.message : ''
  const challenge = authChallenge(message)
  if (challenge) {
    return loginChallengeMessage(challenge)
  }
  if (error instanceof ApiError) {
    if (error.status === 429) {
      return t('feedback.rateLimited')
    }
    if (error.status === 401) {
      return t('auth.invalidCredentials')
    }
    if (error.status === 403) {
      return t('feedback.forbidden')
    }
  }
  return t(fallbackKey)
}

function loginChallengeMessage(message: string): string {
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
  return t('auth.signInFailed')
}

async function submitLogin() {
  if (turnstileEnabled.value && !loginForm.captcha) {
    showAuthNotice('warning', t('auth.captchaRequired'))
    return
  }
  if (!(await loginFormRef.value?.validate().catch(() => false))) {
    return
  }
  clearAuthNotice()
  submitting.value = true
  try {
    await auth.login(loginForm)
    showAuthNotice('success', t('auth.signedIn'))
  } catch (error) {
    const message = error instanceof Error ? error.message : ''
    const challenge = authChallenge(message)
    showAdminMfa.value = challenge === 'admin mfa required' || challenge === 'admin mfa invalid'
    showLoginCaptcha.value =
      turnstileEnabled.value ||
      challenge === 'captcha required' ||
      challenge === 'captcha incorrect'
    if (showLoginCaptcha.value && !turnstileEnabled.value) {
      refreshCaptcha('login')
    }
    showAuthNotice('error', authErrorMessage(error, 'auth.signInFailed'))
  } finally {
    submitting.value = false
  }
}

async function submitRegister() {
  if (turnstileEnabled.value && !registerForm.captcha) {
    showAuthNotice('warning', t('auth.captchaRequired'))
    return
  }
  if (!(await registerFormRef.value?.validate().catch(() => false))) {
    return
  }
  clearAuthNotice()
  submitting.value = true
  try {
    await auth.register({ ...registerForm, avatarFile: avatarFile.value })
    showAuthNotice('success', t('auth.registrationComplete'))
    activeTab.value = 'login'
    loginForm.username = registerForm.username
  } catch (error) {
    if (!turnstileEnabled.value) {
      refreshCaptcha('register')
    }
    showAuthNotice('error', authErrorMessage(error, 'auth.registrationFailed'))
  } finally {
    submitting.value = false
  }
}

async function requestResetCode() {
  if (!(await resetFormRef.value?.validateField(['username', 'phone']).catch(() => false))) {
    return
  }
  if (turnstileEnabled.value && !resetRequestCaptcha.value) {
    showAuthNotice('warning', t('auth.captchaRequired'))
    return
  }
  clearAuthNotice()
  resetRequestPending.value = true
  try {
    await authApi.requestPasswordReset({ ...resetForm, captcha: resetRequestCaptcha.value })
    resetStage.value = 'challenge'
    showAuthNotice('success', t('auth.resetChallengeSent'))
  } catch (error) {
    showAuthNotice('error', authErrorMessage(error, 'auth.requestFailed'))
  } finally {
    resetRequestPending.value = false
  }
}

async function submitReset() {
  if (!(await resetFormRef.value?.validate().catch(() => false))) {
    return
  }
  if (turnstileEnabled.value && !resetForm.captcha) {
    showAuthNotice('warning', t('auth.captchaRequired'))
    return
  }
  clearAuthNotice()
  submitting.value = true
  try {
    await authApi.resetPassword(resetForm)
    showAuthNotice('success', t('auth.passwordResetComplete'))
    resetStage.value = 'identity'
    activeTab.value = 'login'
  } catch (error) {
    showAuthNotice('error', authErrorMessage(error, 'auth.passwordResetFailed'))
  } finally {
    submitting.value = false
  }
}

watch(activeTab, () => {
  clearAuthNotice()
})

onBeforeUnmount(() => {
  if (avatarPreview.value) {
    URL.revokeObjectURL(avatarPreview.value)
  }
})

onMounted(() => {
  void loadCaptchaConfig()
})
</script>

<template>
  <div class="route-view">
    <section class="auth-layout">
      <div class="auth-visual">
        <img :src="monkeyLoginImage" :alt="$t('auth.storefrontAlt')" fetchpriority="high" />
      </div>

      <div class="auth-panel">
        <div
          v-if="authNotice"
          class="auth-feedback auth-notice"
          :class="`auth-notice-${authNotice.level}`"
          :role="authNoticeRole"
          aria-live="polite"
        >
          <el-icon class="auth-notice-icon" aria-hidden="true">
            <component :is="noticeIcon" />
          </el-icon>
          <span>{{ authNotice.message }}</span>
          <el-button
            text
            circle
            :icon="CloseBold"
            :aria-label="$t('common.dismiss')"
            @click="clearAuthNotice"
          />
        </div>
        <el-tabs v-model="activeTab" stretch>
          <el-tab-pane name="login" :label="$t('auth.login')" lazy>
            <el-form
              ref="loginFormRef"
              :model="loginForm"
              :rules="loginRules"
              label-position="top"
              @submit.prevent="submitLogin"
            >
              <el-form-item :label="$t('auth.username')" prop="username">
                <el-input v-model="loginForm.username" autocomplete="username" />
              </el-form-item>
              <el-form-item :label="$t('auth.password')" prop="password">
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
                  :label="$t('auth.loginVerification')"
                />
                <div class="captcha-row">
                  <template v-if="!turnstileEnabled">
                    <el-input v-model="loginForm.captcha" />
                    <button
                      class="captcha-image-button"
                      type="button"
                      :aria-label="$t('auth.refreshLoginCaptcha')"
                      @click="refreshCaptcha('login')"
                    >
                      <img :src="loginCaptchaUrl" :alt="$t('auth.captchaImageAlt')" />
                    </button>
                  </template>
                </div>
              </el-form-item>
              <el-button
                type="primary"
                native-type="submit"
                :loading="submitting"
                class="full-width"
              >
                {{ $t('auth.login') }}
              </el-button>
            </el-form>
          </el-tab-pane>

          <el-tab-pane name="register" :label="$t('auth.register')" lazy>
            <el-form
              ref="registerFormRef"
              :model="registerForm"
              :rules="registerRules"
              label-position="top"
              @submit.prevent="submitRegister"
            >
              <el-form-item :label="$t('auth.username')" prop="username">
                <el-input v-model="registerForm.username" autocomplete="username" />
              </el-form-item>
              <el-form-item :label="$t('auth.password')" prop="password">
                <el-input
                  v-model="registerForm.password"
                  type="password"
                  autocomplete="new-password"
                  show-password
                />
              </el-form-item>
              <el-form-item :label="$t('auth.phone')" prop="phone">
                <el-input v-model="registerForm.phone" />
              </el-form-item>
              <el-form-item :label="$t('auth.email')" prop="email">
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
                  :label="$t('auth.registrationVerification')"
                />
                <div class="captcha-row">
                  <template v-if="!turnstileEnabled">
                    <el-input v-model="registerForm.captcha" />
                    <button
                      class="captcha-image-button"
                      type="button"
                      :aria-label="$t('auth.refreshRegisterCaptcha')"
                      @click="refreshCaptcha('register')"
                    >
                      <img :src="registerCaptchaUrl" :alt="$t('auth.captchaImageAlt')" />
                    </button>
                  </template>
                </div>
              </el-form-item>
              <el-button
                type="primary"
                native-type="submit"
                :loading="submitting"
                class="full-width"
              >
                {{ $t('auth.register') }}
              </el-button>
            </el-form>
          </el-tab-pane>

          <el-tab-pane name="reset" :label="$t('auth.reset')" lazy>
            <el-form
              ref="resetFormRef"
              :model="resetForm"
              :rules="resetRules"
              label-position="top"
              @submit.prevent="resetStage === 'identity' ? requestResetCode() : submitReset()"
            >
              <div v-if="resetStage === 'identity'" class="reset-stage">
                <p class="reset-stage__description">{{ $t('auth.resetIdentityHint') }}</p>
                <el-form-item :label="$t('auth.username')" prop="username">
                  <el-input v-model="resetForm.username" autocomplete="username" />
                </el-form-item>
                <el-form-item :label="$t('auth.phone')" prop="phone">
                  <el-input v-model="resetForm.phone" inputmode="tel" autocomplete="tel" />
                </el-form-item>
                <el-form-item :label="$t('auth.email')" prop="email">
                  <el-input v-model="resetForm.email" inputmode="email" autocomplete="email" />
                </el-form-item>
                <el-form-item v-if="turnstileEnabled" :label="$t('auth.captcha')">
                  <HumanVerification
                    v-model="resetRequestCaptcha"
                    action="password-reset-request"
                    :site-key="captchaConfig.siteKey"
                    :label="$t('auth.resetRequestVerification')"
                  />
                </el-form-item>
                <el-button
                  type="primary"
                  native-type="submit"
                  :loading="resetRequestPending"
                  class="full-width"
                >
                  {{ $t('auth.requestResetCode') }}
                </el-button>
              </div>

              <div v-else class="reset-stage">
                <button class="auth-back-button" type="button" @click="resetStage = 'identity'">
                  <el-icon aria-hidden="true"><Back /></el-icon>
                  <span>{{ $t('auth.changeIdentity') }}</span>
                </button>
                <p class="reset-stage__description">
                  {{ $t('auth.resetChallengeHint', { username: resetForm.username }) }}
                </p>
                <el-form-item :label="$t('auth.otp')">
                  <el-input
                    v-model="resetForm.otp"
                    inputmode="numeric"
                    autocomplete="one-time-code"
                  />
                </el-form-item>
                <el-form-item :label="$t('auth.emailToken')">
                  <el-input v-model="resetForm.emailToken" autocomplete="one-time-code" />
                </el-form-item>
                <el-form-item :label="$t('auth.newPassword')" prop="newPassword">
                  <el-input
                    v-model="resetForm.newPassword"
                    type="password"
                    autocomplete="new-password"
                    show-password
                  />
                </el-form-item>
                <el-form-item v-if="turnstileEnabled" :label="$t('auth.captcha')">
                  <HumanVerification
                    v-model="resetForm.captcha"
                    action="password-reset"
                    :site-key="captchaConfig.siteKey"
                    :label="$t('auth.resetVerification')"
                  />
                </el-form-item>
                <el-button
                  type="primary"
                  native-type="submit"
                  :loading="submitting"
                  class="full-width"
                >
                  {{ $t('auth.reset') }}
                </el-button>
              </div>
            </el-form>
          </el-tab-pane>
        </el-tabs>
        <footer class="auth-footer">
          <RouterLink to="/shop">{{ $t('auth.continueBrowsing') }}</RouterLink>
        </footer>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { Back, Check, Upload } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import * as authApi from '@/api/auth'
import { ApiError } from '@/api/http'
import HumanVerification from '@/components/HumanVerification.vue'
import MascotState, { type MascotPose } from '@/components/mascot/MascotState.vue'
import InlineNotice from '@/components/ui/InlineNotice.vue'
import { evaluatePassword, usePasswordPolicy } from '@/composables/usePasswordPolicy'
import { useRetryCountdown } from '@/composables/useRetryCountdown'
import { useAuthStore } from '@/stores/auth'
import type { CaptchaConfig, PasswordPolicy } from '@/types'

type AuthMode = 'login' | 'register' | 'reset'
type RegisterStep = 'account' | 'contact' | 'complete'
type PasswordResetStage = 'identity' | 'challenge'
type NoticeSeverity = 'info' | 'success' | 'warning' | 'danger'
type RegisterField = 'username' | 'password' | 'phone' | 'email' | 'captcha' | 'avatarFile'

const FALLBACK_PASSWORD_POLICY: PasswordPolicy = {
  minLength: 8,
  requireUppercase: true,
  requireLowercase: true,
  requireDigit: true,
  requireSpecial: true,
  forbidWhitespace: true,
}
const AUTH_MODES: readonly AuthMode[] = ['login', 'register', 'reset']
const REGISTER_FIELDS = new Set<RegisterField>([
  'username',
  'password',
  'phone',
  'email',
  'captcha',
  'avatarFile',
])

const auth = useAuthStore()
const { t } = useI18n()
const passwordPolicyController = usePasswordPolicy()
const retryCountdown = useRetryCountdown()

const activeMode = ref<AuthMode>('login')
const registerStep = ref<RegisterStep>('account')
const resetStage = ref<PasswordResetStage>('identity')
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
const registerFieldErrors = reactive<Partial<Record<RegisterField, string>>>({})
const loginFormRef = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()
const resetFormRef = ref<FormInstance>()

const authNotice = ref<{
  severity: NoticeSeverity
  message: string
  pose?: MascotPose
} | null>(null)
const effectivePasswordPolicy = computed(
  () => passwordPolicyController.policy.value ?? FALLBACK_PASSWORD_POLICY,
)
const passwordEvaluation = computed(() =>
  evaluatePassword(registerForm.password, effectivePasswordPolicy.value),
)
const retryActive = retryCountdown.isActive
const retrySeconds = retryCountdown.remainingSeconds
const submitBlocked = computed(() => submitting.value || retryActive.value)
const resetRequestBlocked = computed(() => resetRequestPending.value || retryActive.value)

const passwordRequirements = computed(() => [
  {
    key: 'minLength',
    met: passwordEvaluation.value.minLength,
    label: t('auth.policyMinLength', { count: effectivePasswordPolicy.value.minLength }),
  },
  {
    key: 'uppercase',
    met: passwordEvaluation.value.uppercase,
    label: t('auth.policyUppercase'),
  },
  {
    key: 'lowercase',
    met: passwordEvaluation.value.lowercase,
    label: t('auth.policyLowercase'),
  },
  { key: 'digit', met: passwordEvaluation.value.digit, label: t('auth.policyDigit') },
  { key: 'special', met: passwordEvaluation.value.special, label: t('auth.policySpecial') },
  {
    key: 'noWhitespace',
    met: passwordEvaluation.value.noWhitespace,
    label: t('auth.policyNoWhitespace'),
  },
])

const mascotPose = computed<MascotPose>(() => {
  if (retryActive.value) return 'hourglass'
  if (registerStep.value === 'complete') return 'celebrate'
  if (authNotice.value?.pose) return authNotice.value.pose
  if (activeMode.value === 'register') return 'clipboard'
  if (activeMode.value === 'reset') return 'shield'
  return 'welcome'
})

const modeTitle = computed(() => {
  if (activeMode.value === 'register') return t('auth.createAccount')
  if (activeMode.value === 'reset') return t('auth.recoverAccount')
  return t('auth.welcomeBack')
})

function meetsPasswordPolicy(password: string): boolean {
  return Object.values(evaluatePassword(password, effectivePasswordPolicy.value)).every(Boolean)
}

function strongPasswordRule() {
  return {
    validator: (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
      const password = typeof value === 'string' ? value : ''
      if (!password) {
        callback(new Error(t('auth.passwordRequired')))
        return
      }
      if (!meetsPasswordPolicy(password)) {
        callback(new Error(t('auth.passwordPolicyIncomplete')))
        return
      }
      callback()
    },
    trigger: ['blur', 'change'],
  }
}

const loginRules = computed<FormRules>(() => ({
  username: [{ required: true, message: t('auth.usernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('auth.passwordRequired'), trigger: 'blur' }],
}))
const registerRules = computed<FormRules>(() => ({
  username: [
    { required: true, message: t('auth.usernameRequired'), trigger: 'blur' },
    { min: 3, max: 32, message: t('auth.usernameLength'), trigger: 'blur' },
  ],
  password: [strongPasswordRule()],
  phone: [
    { required: true, message: t('auth.phoneRequired'), trigger: 'blur' },
    { pattern: /^1\d{6,14}$/, message: t('auth.phoneInvalid'), trigger: 'blur' },
  ],
  email: [{ type: 'email', message: t('auth.emailInvalid'), trigger: 'blur' }],
  captcha: [{ required: true, message: t('auth.captchaRequired'), trigger: 'blur' }],
}))
const resetRules = computed<FormRules>(() => ({
  username: [{ required: true, message: t('auth.usernameRequired'), trigger: 'blur' }],
  phone: [
    { required: true, message: t('auth.phoneRequired'), trigger: 'blur' },
    { pattern: /^1\d{6,14}$/, message: t('auth.phoneInvalid'), trigger: 'blur' },
  ],
  email: [{ type: 'email', message: t('auth.emailInvalid'), trigger: 'blur' }],
  newPassword: [strongPasswordRule()],
}))

function setMode(mode: AuthMode) {
  activeMode.value = mode
}

async function handleModeKeydown(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const currentIndex = AUTH_MODES.indexOf(activeMode.value)
  const nextIndex =
    event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? AUTH_MODES.length - 1
        : (currentIndex + (event.key === 'ArrowRight' ? 1 : -1) + AUTH_MODES.length) %
          AUTH_MODES.length
  const nextMode = AUTH_MODES[nextIndex]
  setMode(nextMode)
  await nextTick()
  document.querySelector<HTMLElement>(`[data-testid="${nextMode}-tab"]`)?.focus()
}

function showAuthNotice(severity: NoticeSeverity, message: string, pose?: MascotPose) {
  authNotice.value = { severity, message, pose }
}

function clearAuthNotice() {
  authNotice.value = null
}

async function loadAuthMetadata() {
  await Promise.allSettled([
    authApi.captchaConfig().then((config) => {
      captchaConfig.value = config
      showLoginCaptcha.value = config.provider === 'turnstile'
    }),
    passwordPolicyController.load(),
  ])
}

function refreshCaptcha(scope: 'login' | 'register') {
  if (scope === 'login') {
    loginCaptchaUrl.value = authApi.captchaUrl('auth')
    loginForm.captcha = ''
  } else {
    registerCaptchaUrl.value = authApi.captchaUrl('auth')
    registerForm.captcha = ''
  }
}

function selectAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
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

function loginChallengeMessage(message: string): string {
  if (message === 'admin mfa required') return t('auth.adminMfaRequired')
  if (message === 'admin mfa invalid') return t('auth.adminMfaInvalid')
  if (message === 'captcha required') return t('auth.captchaRequired')
  if (message === 'captcha incorrect') return t('auth.captchaIncorrect')
  return t('auth.signInFailed')
}

function authErrorMessage(error: unknown, fallbackKey: string): string {
  const message = error instanceof Error ? error.message : ''
  const challenge = authChallenge(message)
  if (challenge) return loginChallengeMessage(challenge)
  if (error instanceof ApiError) {
    if (error.status === 429) return t('feedback.rateLimited')
    if (error.status === 401) return t('auth.invalidCredentials')
    if (error.status === 403) return t('feedback.forbidden')
  }
  return t(fallbackKey)
}

function handleAuthError(error: unknown, fallbackKey: string) {
  if (error instanceof ApiError && error.status === 429) {
    retryCountdown.start(error)
    showAuthNotice('warning', t('feedback.rateLimited'), 'hourglass')
    return
  }
  const pose: MascotPose = error instanceof ApiError && error.status === 403 ? 'shield' : 'warning'
  showAuthNotice('danger', authErrorMessage(error, fallbackKey), pose)
}

function clearRegisterFieldError(field: RegisterField) {
  delete registerFieldErrors[field]
}

function clearRegisterFieldErrors() {
  for (const field of REGISTER_FIELDS) delete registerFieldErrors[field]
}

function applyRegisterFieldErrors(error: unknown): boolean {
  if (!(error instanceof ApiError) || error.status !== 422 || !error.fieldErrors?.length)
    return false
  clearRegisterFieldErrors()
  for (const violation of error.fieldErrors) {
    if (!REGISTER_FIELDS.has(violation.field as RegisterField)) continue
    const field = violation.field as RegisterField
    registerFieldErrors[field] = violation.message.trim() || t('auth.invalidField')
  }
  return Object.keys(registerFieldErrors).length > 0
}

async function submitLogin() {
  if (retryActive.value) return
  if ((showLoginCaptcha.value || turnstileEnabled.value) && !loginForm.captcha) {
    showAuthNotice('warning', t('auth.captchaRequired'), 'shield')
    return
  }
  if (!(await loginFormRef.value?.validate().catch(() => false))) return
  clearAuthNotice()
  submitting.value = true
  try {
    await auth.login(loginForm)
    retryCountdown.clear()
  } catch (error) {
    const challenge = authChallenge(error instanceof Error ? error.message : '')
    showAdminMfa.value = challenge === 'admin mfa required' || challenge === 'admin mfa invalid'
    showLoginCaptcha.value =
      turnstileEnabled.value ||
      challenge === 'captcha required' ||
      challenge === 'captcha incorrect'
    if (showLoginCaptcha.value && !turnstileEnabled.value) refreshCaptcha('login')
    handleAuthError(error, 'auth.signInFailed')
  } finally {
    submitting.value = false
  }
}

async function continueRegistration() {
  if (!(await registerFormRef.value?.validateField(['username', 'password']).catch(() => false))) {
    return
  }
  registerStep.value = 'contact'
  clearAuthNotice()
}

async function submitRegister() {
  if (retryActive.value) return
  if (!registerForm.username.trim() || !meetsPasswordPolicy(registerForm.password)) {
    registerStep.value = 'account'
    await nextTick()
    await registerFormRef.value?.validateField(['username', 'password']).catch(() => false)
    return
  }
  if (!(await registerFormRef.value?.validate().catch(() => false))) return

  clearAuthNotice()
  clearRegisterFieldErrors()
  submitting.value = true
  try {
    await auth.register({ ...registerForm, avatarFile: avatarFile.value })
    retryCountdown.clear()
    loginForm.username = registerForm.username
    registerStep.value = 'complete'
    showAuthNotice('success', t('auth.registrationComplete'), 'celebrate')
  } catch (error) {
    if (!turnstileEnabled.value) refreshCaptcha('register')
    if (applyRegisterFieldErrors(error)) {
      registerStep.value =
        registerFieldErrors.username || registerFieldErrors.password ? 'account' : 'contact'
      showAuthNotice('danger', t('auth.fixHighlightedFields'), 'shield')
    } else {
      handleAuthError(error, 'auth.registrationFailed')
    }
  } finally {
    submitting.value = false
  }
}

function finishRegistration() {
  registerStep.value = 'account'
  activeMode.value = 'login'
  clearAuthNotice()
}

async function requestResetCode() {
  if (retryActive.value) return
  if (!(await resetFormRef.value?.validateField(['username', 'phone']).catch(() => false))) return
  if (turnstileEnabled.value && !resetRequestCaptcha.value) {
    showAuthNotice('warning', t('auth.captchaRequired'), 'shield')
    return
  }
  clearAuthNotice()
  resetRequestPending.value = true
  try {
    await authApi.requestPasswordReset({ ...resetForm, captcha: resetRequestCaptcha.value })
    retryCountdown.clear()
    resetStage.value = 'challenge'
    showAuthNotice('success', t('auth.resetChallengeSent'), 'shield')
  } catch (error) {
    handleAuthError(error, 'auth.requestFailed')
  } finally {
    resetRequestPending.value = false
  }
}

async function submitReset() {
  if (retryActive.value) return
  if (!(await resetFormRef.value?.validate().catch(() => false))) return
  if (turnstileEnabled.value && !resetForm.captcha) {
    showAuthNotice('warning', t('auth.captchaRequired'), 'shield')
    return
  }
  clearAuthNotice()
  submitting.value = true
  try {
    await authApi.resetPassword(resetForm)
    retryCountdown.clear()
    resetStage.value = 'identity'
    activeMode.value = 'login'
    showAuthNotice('success', t('auth.passwordResetComplete'), 'celebrate')
  } catch (error) {
    handleAuthError(error, 'auth.passwordResetFailed')
  } finally {
    submitting.value = false
  }
}

watch(activeMode, () => {
  if (!retryActive.value) clearAuthNotice()
})
watch(
  () => registerForm.username,
  () => clearRegisterFieldError('username'),
)
watch(
  () => registerForm.password,
  () => clearRegisterFieldError('password'),
)
watch(
  () => registerForm.phone,
  () => clearRegisterFieldError('phone'),
)
watch(
  () => registerForm.email,
  () => clearRegisterFieldError('email'),
)
watch(
  () => registerForm.captcha,
  () => clearRegisterFieldError('captcha'),
)

onBeforeUnmount(() => {
  if (avatarPreview.value) URL.revokeObjectURL(avatarPreview.value)
})

onMounted(() => {
  void loadAuthMetadata()
})
</script>

<template>
  <div class="route-view auth-page">
    <section class="auth-workspace" aria-labelledby="auth-page-title">
      <header class="auth-brand-region">
        <div class="auth-brand-copy">
          <p class="auth-eyebrow">{{ $t('auth.secureAccess') }}</p>
          <h1 id="auth-page-title">MonkeyShop</h1>
          <p>{{ $t('auth.workspaceDescription') }}</p>
        </div>
        <div class="auth-mascot-frame">
          <MascotState :pose="mascotPose" size="md" :alt="$t('auth.mascotAlt')" eager />
        </div>
      </header>

      <section class="auth-surface" :aria-labelledby="`auth-panel-${activeMode}`">
        <div
          class="auth-mode-switch"
          role="tablist"
          tabindex="-1"
          :aria-label="$t('auth.modeNavigation')"
          @keydown="handleModeKeydown"
        >
          <button
            id="auth-tab-login"
            data-testid="login-tab"
            class="auth-mode-tab"
            type="button"
            role="tab"
            :aria-selected="activeMode === 'login'"
            aria-controls="auth-panel-login"
            :tabindex="activeMode === 'login' ? 0 : -1"
            @click="setMode('login')"
          >
            {{ $t('auth.login') }}
          </button>
          <button
            id="auth-tab-register"
            data-testid="register-tab"
            class="auth-mode-tab"
            type="button"
            role="tab"
            :aria-selected="activeMode === 'register'"
            aria-controls="auth-panel-register"
            :tabindex="activeMode === 'register' ? 0 : -1"
            @click="setMode('register')"
          >
            {{ $t('auth.register') }}
          </button>
          <button
            id="auth-tab-reset"
            data-testid="reset-tab"
            class="auth-mode-tab"
            type="button"
            role="tab"
            :aria-selected="activeMode === 'reset'"
            aria-controls="auth-panel-reset"
            :tabindex="activeMode === 'reset' ? 0 : -1"
            @click="setMode('reset')"
          >
            {{ $t('auth.reset') }}
          </button>
        </div>

        <InlineNotice
          v-if="retryActive"
          class="auth-inline-notice"
          severity="warning"
          :message="$t('feedback.rateLimited')"
        >
          <p data-testid="retry-countdown" data-numeric>
            {{ $t('auth.retryCountdown', { seconds: retrySeconds }) }}
          </p>
        </InlineNotice>
        <InlineNotice
          v-else-if="authNotice"
          class="auth-inline-notice"
          :severity="authNotice.severity"
          :message="authNotice.message"
          dismissible
          @dismiss="clearAuthNotice"
        />

        <section
          v-if="activeMode === 'login'"
          id="auth-panel-login"
          class="auth-mode-panel"
          role="tabpanel"
          aria-labelledby="auth-tab-login"
        >
          <header class="auth-form-heading">
            <h2>{{ modeTitle }}</h2>
            <p>{{ $t('auth.loginDescription') }}</p>
          </header>
          <el-form
            ref="loginFormRef"
            :model="loginForm"
            :rules="loginRules"
            label-position="top"
            @submit.prevent="submitLogin"
          >
            <el-form-item :label="$t('auth.username')" prop="username">
              <el-input
                v-model="loginForm.username"
                data-testid="login-username"
                autocomplete="username"
              />
            </el-form-item>
            <el-form-item :label="$t('auth.password')" prop="password">
              <el-input
                v-model="loginForm.password"
                data-testid="login-password"
                type="password"
                autocomplete="current-password"
                show-password
              />
            </el-form-item>
            <el-form-item v-if="showAdminMfa" :label="$t('auth.totp')">
              <el-input v-model="loginForm.totp" inputmode="numeric" autocomplete="one-time-code" />
            </el-form-item>
            <el-form-item v-if="showLoginCaptcha || turnstileEnabled" :label="$t('auth.captcha')">
              <HumanVerification
                v-if="turnstileEnabled"
                v-model="loginForm.captcha"
                action="login"
                :site-key="captchaConfig.siteKey"
                :label="$t('auth.loginVerification')"
              />
              <div v-else class="captcha-row">
                <el-input v-model="loginForm.captcha" />
                <button
                  class="captcha-image-button"
                  type="button"
                  :aria-label="$t('auth.refreshLoginCaptcha')"
                  @click="refreshCaptcha('login')"
                >
                  <img :src="loginCaptchaUrl" :alt="$t('auth.captchaImageAlt')" />
                </button>
              </div>
            </el-form-item>
            <el-button
              data-testid="login-submit"
              type="primary"
              native-type="submit"
              :loading="submitting"
              :disabled="submitBlocked"
              class="auth-primary-action"
            >
              {{ $t('auth.login') }}
            </el-button>
          </el-form>
        </section>

        <section
          v-else-if="activeMode === 'register'"
          id="auth-panel-register"
          class="auth-mode-panel"
          role="tabpanel"
          aria-labelledby="auth-tab-register"
        >
          <header class="auth-form-heading">
            <h2>{{ modeTitle }}</h2>
            <p>{{ $t('auth.registrationDescription') }}</p>
          </header>

          <ol class="register-stepper" :aria-label="$t('auth.registrationProgress')">
            <li :data-state="registerStep === 'account' ? 'active' : 'complete'">
              <span>1</span>{{ $t('auth.stepAccount') }}
            </li>
            <li
              :data-state="
                registerStep === 'contact'
                  ? 'active'
                  : registerStep === 'complete'
                    ? 'complete'
                    : 'pending'
              "
            >
              <span>2</span>{{ $t('auth.stepContact') }}
            </li>
            <li :data-state="registerStep === 'complete' ? 'active' : 'pending'">
              <span>3</span>{{ $t('auth.stepComplete') }}
            </li>
          </ol>

          <div
            v-if="registerStep === 'complete'"
            data-testid="register-complete"
            class="register-complete"
          >
            <h3>{{ $t('auth.registrationComplete') }}</h3>
            <p>
              {{ $t('auth.registrationCompleteDescription', { username: loginForm.username }) }}
            </p>
            <el-button type="primary" class="auth-primary-action" @click="finishRegistration">
              {{ $t('auth.signInNow') }}
            </el-button>
          </div>

          <el-form
            v-else
            ref="registerFormRef"
            :model="registerForm"
            :rules="registerRules"
            label-position="top"
            @submit.prevent="registerStep === 'account' ? continueRegistration() : submitRegister()"
          >
            <div
              v-if="registerStep === 'account'"
              data-testid="register-account-step"
              class="register-step-panel"
            >
              <el-form-item :label="$t('auth.username')" prop="username">
                <el-input
                  v-model="registerForm.username"
                  data-testid="register-username"
                  autocomplete="username"
                  :aria-describedby="
                    registerFieldErrors.username ? 'register-username-error' : undefined
                  "
                />
                <p
                  v-if="registerFieldErrors.username"
                  id="register-username-error"
                  data-field-error="username"
                  class="server-field-error"
                  role="alert"
                >
                  {{ registerFieldErrors.username }}
                </p>
              </el-form-item>
              <el-form-item :label="$t('auth.password')" prop="password">
                <el-input
                  v-model="registerForm.password"
                  data-testid="register-password"
                  type="password"
                  autocomplete="new-password"
                  show-password
                  :aria-describedby="
                    registerFieldErrors.password ? 'register-password-error' : 'password-policy'
                  "
                />
                <p
                  v-if="registerFieldErrors.password"
                  id="register-password-error"
                  data-field-error="password"
                  class="server-field-error"
                  role="alert"
                >
                  {{ registerFieldErrors.password }}
                </p>
              </el-form-item>
              <ul
                id="password-policy"
                class="password-policy"
                :aria-label="$t('auth.passwordPolicy')"
              >
                <li
                  v-for="requirement in passwordRequirements"
                  :key="requirement.key"
                  :data-met="requirement.met"
                >
                  <el-icon aria-hidden="true"><Check /></el-icon>
                  <span>{{ requirement.label }}</span>
                </li>
              </ul>
              <el-button
                data-testid="register-next"
                type="primary"
                native-type="submit"
                class="auth-primary-action"
              >
                {{ $t('common.next') }}
              </el-button>
            </div>

            <div v-else data-testid="register-contact-step" class="register-step-panel">
              <button class="auth-back-button" type="button" @click="registerStep = 'account'">
                <el-icon aria-hidden="true"><Back /></el-icon>
                <span>{{ $t('common.back') }}</span>
              </button>
              <el-form-item :label="$t('auth.phone')" prop="phone">
                <el-input
                  v-model="registerForm.phone"
                  data-testid="register-phone"
                  inputmode="tel"
                  autocomplete="tel"
                />
                <p
                  v-if="registerFieldErrors.phone"
                  data-field-error="phone"
                  class="server-field-error"
                  role="alert"
                >
                  {{ registerFieldErrors.phone }}
                </p>
              </el-form-item>
              <el-form-item :label="$t('auth.email')" prop="email">
                <el-input
                  v-model="registerForm.email"
                  data-testid="register-email"
                  inputmode="email"
                  autocomplete="email"
                />
                <p
                  v-if="registerFieldErrors.email"
                  data-field-error="email"
                  class="server-field-error"
                  role="alert"
                >
                  {{ registerFieldErrors.email }}
                </p>
              </el-form-item>
              <el-form-item :label="$t('auth.avatar')">
                <label class="file-picker" for="register-avatar-input">
                  <el-icon aria-hidden="true"><Upload /></el-icon>
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
              <el-form-item :label="$t('auth.captcha')" prop="captcha">
                <HumanVerification
                  v-if="turnstileEnabled"
                  v-model="registerForm.captcha"
                  action="register"
                  :site-key="captchaConfig.siteKey"
                  :label="$t('auth.registrationVerification')"
                />
                <div v-else class="captcha-row">
                  <el-input v-model="registerForm.captcha" data-testid="register-captcha" />
                  <button
                    class="captcha-image-button"
                    type="button"
                    :aria-label="$t('auth.refreshRegisterCaptcha')"
                    @click="refreshCaptcha('register')"
                  >
                    <img :src="registerCaptchaUrl" :alt="$t('auth.captchaImageAlt')" />
                  </button>
                </div>
                <p
                  v-if="registerFieldErrors.captcha"
                  data-field-error="captcha"
                  class="server-field-error"
                  role="alert"
                >
                  {{ registerFieldErrors.captcha }}
                </p>
              </el-form-item>
              <el-button
                data-testid="register-submit"
                type="primary"
                native-type="submit"
                :loading="submitting"
                :disabled="submitBlocked"
                class="auth-primary-action"
              >
                {{ $t('auth.createAccountAction') }}
              </el-button>
            </div>
          </el-form>
        </section>

        <section
          v-else
          id="auth-panel-reset"
          class="auth-mode-panel"
          role="tabpanel"
          aria-labelledby="auth-tab-reset"
        >
          <header class="auth-form-heading">
            <h2>{{ modeTitle }}</h2>
            <p>{{ $t('auth.resetIdentityHint') }}</p>
          </header>
          <el-form
            ref="resetFormRef"
            :model="resetForm"
            :rules="resetRules"
            label-position="top"
            @submit.prevent="resetStage === 'identity' ? requestResetCode() : submitReset()"
          >
            <div v-if="resetStage === 'identity'" class="reset-stage">
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
                :disabled="resetRequestBlocked"
                class="auth-primary-action"
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
                :disabled="submitBlocked"
                class="auth-primary-action"
              >
                {{ $t('auth.reset') }}
              </el-button>
            </div>
          </el-form>
        </section>

        <footer class="auth-footer">
          <RouterLink to="/shop">{{ $t('auth.continueBrowsing') }}</RouterLink>
        </footer>
      </section>
    </section>
  </div>
</template>

<style scoped>
.auth-page {
  min-width: 0;
}

.auth-workspace {
  display: grid;
  gap: var(--space-5);
  width: min(920px, 100%);
  margin: 0 auto;
  padding-block: var(--space-3) var(--space-8);
}

.auth-brand-region {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 192px;
  gap: var(--space-6);
  align-items: center;
  min-height: 190px;
  padding-inline: clamp(var(--space-2), 4vw, var(--space-8));
}

.auth-brand-copy {
  min-width: 0;
  max-width: 58ch;
}

.auth-eyebrow {
  margin: 0 0 var(--space-2);
  color: var(--color-cobalt);
  font-size: var(--text-sm);
  font-weight: 800;
}

.auth-brand-copy h1 {
  margin: 0;
  color: var(--color-ink);
  font-size: var(--text-4xl);
  line-height: 1.12;
  overflow-wrap: anywhere;
}

.auth-brand-copy > p:last-child {
  max-width: 52ch;
  margin: var(--space-3) 0 0;
  color: var(--color-muted);
  line-height: var(--leading-relaxed);
}

.auth-mascot-frame {
  width: 192px;
  justify-self: end;
}

.auth-surface {
  display: grid;
  gap: var(--space-5);
  width: min(680px, 100%);
  justify-self: center;
  padding: var(--space-6);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  background: var(--color-surface);
  box-shadow: var(--shadow-surface);
}

.auth-mode-switch {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-1);
  padding: var(--space-1);
  border: 1px solid var(--color-line);
  border-radius: var(--radius-surface);
  background: var(--color-surface-subtle);
}

.auth-mode-tab {
  min-width: 0;
  min-height: 44px;
  padding: var(--space-2) var(--space-3);
  border: 0;
  border-radius: var(--radius-control);
  color: var(--color-ink);
  background: transparent;
  font-weight: 750;
  cursor: pointer;
}

.auth-mode-tab[aria-selected='true'] {
  color: var(--color-primary);
  background: var(--color-surface);
  box-shadow: var(--shadow-control);
}

.auth-inline-notice :deep(p) {
  margin: var(--space-1) 0 0;
  color: var(--color-muted);
  font-size: var(--text-xs);
}

.auth-mode-panel,
.auth-mode-panel form,
.register-step-panel,
.reset-stage {
  display: grid;
  gap: var(--space-4);
  min-width: 0;
}

.auth-form-heading h2,
.register-complete h3 {
  margin: 0;
  color: var(--color-ink);
  font-size: var(--text-xl);
  line-height: var(--leading-tight);
}

.auth-form-heading p,
.register-complete p,
.reset-stage__description {
  max-width: 58ch;
  margin: var(--space-2) 0 0;
  color: var(--color-muted);
  font-size: var(--text-sm);
  line-height: var(--leading-relaxed);
}

.auth-primary-action {
  width: 100%;
  min-height: 46px;
}

.register-stepper {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.register-stepper li {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  min-width: 0;
  color: var(--color-muted);
  font-size: var(--text-xs);
  font-weight: 700;
}

.register-stepper li > span {
  display: inline-grid;
  place-items: center;
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-circle);
  background: var(--color-surface);
  font-variant-numeric: tabular-nums;
}

.register-stepper li[data-state='active'] {
  color: var(--color-primary);
}

.register-stepper li[data-state='active'] > span,
.register-stepper li[data-state='complete'] > span {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background: var(--color-primary-soft);
}

.password-policy {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2) var(--space-4);
  margin: calc(var(--space-2) * -1) 0 0;
  padding: 0;
  list-style: none;
}

.password-policy li {
  display: flex;
  gap: var(--space-2);
  align-items: flex-start;
  color: var(--color-muted);
  font-size: var(--text-xs);
  line-height: var(--leading-normal);
}

.password-policy li[data-met='true'] {
  color: var(--color-primary);
}

.password-policy .el-icon {
  flex: 0 0 auto;
  margin-top: 2px;
}

.server-field-error {
  width: 100%;
  margin: var(--space-1) 0 0;
  color: var(--color-danger);
  font-size: var(--text-xs);
  line-height: var(--leading-normal);
}

.captcha-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  gap: var(--space-2);
  width: 100%;
}

.file-picker {
  max-width: 100%;
}

.file-picker span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.register-complete {
  display: grid;
  gap: var(--space-3);
  justify-items: start;
  padding-block: var(--space-3);
}

.auth-back-button {
  display: inline-flex;
  gap: var(--space-2);
  align-items: center;
  justify-self: start;
  min-height: 40px;
  padding: 0;
  border: 0;
  color: var(--color-primary);
  background: transparent;
  font-weight: 700;
  cursor: pointer;
}

.auth-footer {
  display: flex;
  justify-content: center;
  padding-top: var(--space-4);
  border-top: 1px solid var(--color-line);
}

.auth-footer a {
  color: var(--color-primary);
  font-weight: 700;
}

@media (max-width: 640px) {
  .auth-workspace {
    gap: var(--space-3);
    padding-block: 0 var(--space-5);
  }

  .auth-brand-region {
    grid-template-columns: minmax(0, 1fr) 128px;
    gap: var(--space-3);
    min-height: 142px;
    padding-inline: 0;
  }

  .auth-brand-copy h1 {
    font-size: var(--text-3xl);
  }

  .auth-brand-copy > p:last-child {
    margin-top: var(--space-2);
    font-size: var(--text-sm);
  }

  .auth-mascot-frame {
    width: 128px;
  }

  .auth-surface {
    gap: var(--space-4);
    padding: var(--space-4);
  }

  .auth-mode-tab {
    min-height: 48px;
    padding-inline: var(--space-2);
  }

  .password-policy {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 340px) {
  .auth-brand-region {
    grid-template-columns: minmax(0, 1fr) 96px;
  }

  .auth-mascot-frame {
    width: 96px;
  }

  .auth-mode-tab {
    font-size: var(--text-xs);
  }

  .register-stepper li {
    display: grid;
    justify-items: center;
    text-align: center;
  }
}
</style>

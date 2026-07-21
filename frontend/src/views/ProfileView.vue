<script setup lang="ts">
import { Check, Edit, Lock, Location, Refresh, Upload } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { captchaConfig as loadCaptchaConfig, captchaUrl } from '@/api/auth'
import { uploadImage } from '@/api/catalog'
import type { PageEnvelope } from '@/api/page'
import * as userApi from '@/api/user'
import HumanVerification from '@/components/HumanVerification.vue'
import MascotState from '@/components/mascot/MascotState.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import { useAuthStore } from '@/stores/auth'
import type { Address, CaptchaConfig, UserProfile } from '@/types'

type ProfileTab = 'identity' | 'addresses' | 'security' | 'privacy'

const FORGET_PHRASE = 'FORGET'
const defaultAvatar = '/images/default_avatar.jpg'
const activeTab = ref<ProfileTab>('identity')
const userCaptchaUrl = ref(captchaUrl('user'))
const captchaConfig = ref<CaptchaConfig>({ provider: 'local', siteKey: '' })
const addressForm = reactive({ receiverName: '', phone: '', detailAddress: '' })
const passwordForm = reactive({ oldPassword: '', phone: '', newPassword: '', captcha: '' })
const editForm = reactive({ receiverName: '', phone: '', detailAddress: '' })
const forgetConfirmation = ref('')
const addressFormRef = ref<FormInstance>()
const passwordFormRef = ref<FormInstance>()
const editFormRef = ref<FormInstance>()
const currentPasswordInput = ref<{ focus: () => void }>()
const passwordFormError = ref('')
const editDialogOpen = ref(false)
const forgetDialogOpen = ref(false)
const editingAddressId = ref<number | null>(null)
const editTrigger = ref<HTMLElement | null>(null)
const editSnapshot = ref('')
const allowRouteLeave = ref(false)
const pending = reactive({
  avatar: false,
  password: false,
  addressCreate: false,
  addressEdit: false,
  forget: false,
})
const deletingAddressIds = reactive(new Set<number>())
const defaultAddressIds = reactive(new Set<number>())
const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const notify = useNotify()
const profileResource = useAsyncState<UserProfile>({ timeoutMs: 20000 })
const addressResource = useAsyncState<PageEnvelope<Address>>({
  timeoutMs: 20000,
  preserveData: true,
})
const addressPageNumber = ref(0)
const addressPageSize = 8

const profile = computed<UserProfile>(() => profileResource.data.value ?? {})
const addressPage = computed(() => addressResource.data.value)
const addresses = computed<Address[]>(() => addressPage.value?.content ?? [])
const addressCount = computed(() => addressPage.value?.totalElements ?? 0)
const turnstileEnabled = computed(() => captchaConfig.value.provider === 'turnstile')
const addressRules = computed<FormRules>(() => ({
  receiverName: [{ required: true, message: t('common.receiverRequired'), trigger: 'blur' }],
  phone: [
    { required: true, message: t('auth.phoneRequired'), trigger: 'blur' },
    { pattern: /^1\d{6,14}$/, message: t('auth.phoneInvalid'), trigger: 'blur' },
  ],
  detailAddress: [{ required: true, message: t('common.addressDetailRequired'), trigger: 'blur' }],
}))
const passwordRules = computed<FormRules>(() => ({
  oldPassword: [{ required: true, message: t('auth.passwordRequired'), trigger: 'blur' }],
  phone: [
    { required: true, message: t('auth.phoneRequired'), trigger: 'blur' },
    { pattern: /^1\d{6,14}$/, message: t('auth.phoneInvalid'), trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: t('auth.passwordRequired'), trigger: 'blur' },
    { min: 8, message: t('auth.passwordMinLength'), trigger: 'blur' },
  ],
}))
const phoneLabel = computed(() => {
  if (profile.value.maskedPhone === 'not bound') return t('auth.phoneNotBound')
  return profile.value.maskedPhone || t('auth.phoneNotBound')
})
const profileMeta = computed(() => {
  const identityLabel =
    profile.value.identity === 'ADMIN'
      ? t('nav.admin')
      : profile.value.identity === 'USER'
        ? t('profile.memberAccount')
        : profile.value.identity
  const meta = [identityLabel, phoneLabel.value].filter(Boolean)
  return meta.length ? meta.join(' / ') : t('profile.accountPending')
})
const passwordButtonLabel = computed(() =>
  profile.value.passwordChangeRequired
    ? t('auth.completePasswordUpdate')
    : t('auth.updatePassword'),
)
const editDirty = computed(
  () => editDialogOpen.value && serializeAddressForm(editForm) !== editSnapshot.value,
)
const addressCreateDirty = computed(() =>
  Object.values(addressForm).some((value) => value.trim().length > 0),
)
const passwordDirty = computed(() =>
  Object.values(passwordForm).some((value) => value.trim().length > 0),
)
const privacyDirty = computed(
  () => forgetDialogOpen.value && forgetConfirmation.value.trim().length > 0,
)
const profileDirty = computed(
  () => editDirty.value || addressCreateDirty.value || passwordDirty.value || privacyDirty.value,
)
const canForget = computed(() => forgetConfirmation.value === FORGET_PHRASE)

function serializeAddressForm(value: {
  receiverName: string
  phone: string
  detailAddress: string
}): string {
  return JSON.stringify({
    receiverName: value.receiverName.trim(),
    phone: value.phone.trim(),
    detailAddress: value.detailAddress.trim(),
  })
}

function maskName(value: string): string {
  const characters = Array.from(value.trim())
  if (characters.length === 0) return ''
  if (characters.length === 1) return '*'
  return `${characters[0]}${'*'.repeat(Math.min(3, characters.length - 1))}`
}

function maskPhone(value: string): string {
  const normalized = value.trim()
  if (normalized.length <= 7) return '*'.repeat(Math.max(1, normalized.length))
  return `${normalized.slice(0, 3)}${'*'.repeat(Math.max(4, normalized.length - 7))}${normalized.slice(-4)}`
}

function onAvatarError(event: Event) {
  const target = event.target as HTMLImageElement
  if (!target.dataset.fallback) {
    target.dataset.fallback = '1'
    target.src = defaultAvatar
  }
}

async function validateForm(form: FormInstance | undefined): Promise<boolean> {
  if (!form) return false
  return form.validate().then(
    () => true,
    () => false,
  )
}

async function loadAccount() {
  const nextProfile = await profileResource.load(({ signal }) => userApi.profile(signal), {
    preserveData: true,
  })
  if (!nextProfile) return
  if (nextProfile.passwordChangeRequired) {
    activeTab.value = 'security'
    addressResource.reset()
    return
  }
  await loadAddresses(addressPageNumber.value)
}

async function loadAddresses(pageNumber = addressPageNumber.value) {
  addressPageNumber.value = pageNumber
  return addressResource.load(
    ({ signal }) =>
      userApi.addressPage({
        page: pageNumber,
        size: addressPageSize,
        sort: 'isDefault,desc',
        signal,
      }),
    {
      preserveData: true,
      isEmpty: (page) => page.content.length === 0,
    },
  )
}

async function refreshAddresses(pageNumber = addressPageNumber.value) {
  return loadAddresses(pageNumber)
}

function changeAddressPage(pageNumber: number) {
  void loadAddresses(pageNumber - 1)
}

async function changeAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || pending.avatar || profile.value.passwordChangeRequired) return

  pending.avatar = true
  try {
    const uploaded = await uploadImage(file, 'avatar')
    await userApi.updateAvatar(uploaded.path)
    notify.success(t('auth.avatarUpdated'), { key: 'profile:avatar:updated' })
    await loadAccount()
  } catch (caught) {
    notify.fromApiError(caught, 'common.unableToUploadImage')
  } finally {
    input.value = ''
    pending.avatar = false
  }
}

function openEditDialog(address: Address, event: Event) {
  editingAddressId.value = address.id
  Object.assign(editForm, {
    receiverName: address.receiverName,
    phone: address.phone,
    detailAddress: address.detailAddress,
  })
  editSnapshot.value = serializeAddressForm(editForm)
  editTrigger.value = event.currentTarget as HTMLElement
  editDialogOpen.value = true
  void nextTick(() => editFormRef.value?.clearValidate())
}

function handleEditDialogClosed() {
  const addressId = editingAddressId.value
  const fallback = editTrigger.value
  editingAddressId.value = null
  editTrigger.value = null
  editSnapshot.value = ''
  editFormRef.value?.clearValidate()
  void nextTick(() => {
    const selector = addressId === null ? '' : `[data-address-edit="${addressId}"]`
    const target = selector ? document.querySelector<HTMLElement>(selector) : null
    ;(target ?? fallback)?.focus()
  })
}

async function confirmDiscardChanges(): Promise<boolean> {
  return notify.confirm({
    title: t('profile.unsavedTitle'),
    content: t('profile.unsavedContent'),
    confirmText: t('common.ok'),
  })
}

async function beforeEditDialogClose(done: () => void) {
  if (!editDirty.value || (await confirmDiscardChanges())) done()
}

async function closeEditDialog() {
  if (!editDirty.value || (await confirmDiscardChanges())) editDialogOpen.value = false
}

async function submitEditAddress() {
  if (
    editingAddressId.value === null ||
    pending.addressEdit ||
    !(await validateForm(editFormRef.value))
  ) {
    return
  }

  pending.addressEdit = true
  try {
    await userApi.updateAddress(editingAddressId.value, { ...editForm })
    await refreshAddresses()
    editSnapshot.value = serializeAddressForm(editForm)
    notify.success(t('common.updated'), {
      key: `profile:address:${editingAddressId.value}:updated`,
    })
    editDialogOpen.value = false
  } catch (caught) {
    notify.fromApiError(caught, 'common.operationFailed')
  } finally {
    pending.addressEdit = false
  }
}

async function saveAddress() {
  if (pending.addressCreate || !(await validateForm(addressFormRef.value))) return

  pending.addressCreate = true
  try {
    await userApi.addAddress({ ...addressForm })
    await refreshAddresses(0)
    notify.success(t('common.updated'), { key: 'profile:address:created' })
    Object.assign(addressForm, { receiverName: '', phone: '', detailAddress: '' })
    addressFormRef.value?.clearValidate()
  } catch (caught) {
    notify.fromApiError(caught, 'common.operationFailed')
  } finally {
    pending.addressCreate = false
  }
}

async function removeAddress(id: number) {
  const confirmed = await notify.confirm({
    title: t('common.confirm'),
    content: t('auth.deleteAddressConfirm'),
    type: 'warning',
  })
  if (!confirmed || deletingAddressIds.has(id)) return

  deletingAddressIds.add(id)
  try {
    await userApi.deleteAddress(id)
    const refreshed = await refreshAddresses()
    if (refreshed?.content.length === 0 && addressPageNumber.value > 0) {
      await refreshAddresses(addressPageNumber.value - 1)
    }
    notify.success(t('common.updated'), { key: `profile:address:${id}:deleted` })
  } catch (caught) {
    notify.fromApiError(caught, 'common.operationFailed')
  } finally {
    deletingAddressIds.delete(id)
  }
}

async function setDefaultAddress(id: number, enabled: boolean) {
  if (!enabled || defaultAddressIds.has(id)) return

  defaultAddressIds.add(id)
  try {
    await userApi.setDefaultAddress(id)
    await refreshAddresses(0)
    notify.success(t('common.updated'), { key: `profile:address:${id}:default` })
  } catch (caught) {
    notify.fromApiError(caught, 'common.operationFailed')
  } finally {
    defaultAddressIds.delete(id)
  }
}

function focusPasswordForm() {
  activeTab.value = 'security'
  void nextTick(() => currentPasswordInput.value?.focus())
}

async function changePassword() {
  passwordFormError.value = ''
  if (pending.password || !(await validateForm(passwordFormRef.value))) return
  if (!passwordForm.captcha.trim()) {
    passwordFormError.value = t('auth.captchaRequired')
    return
  }

  pending.password = true
  try {
    await userApi.updatePassword({ ...passwordForm })
    notify.success(t('auth.passwordChanged'), { key: 'profile:password:changed' })
    Object.assign(passwordForm, { oldPassword: '', phone: '', newPassword: '', captcha: '' })
    userCaptchaUrl.value = captchaUrl('user')
    auth.clearLocalSession()
    await router.push('/login')
  } catch (caught) {
    notify.fromApiError(caught, 'auth.requestFailed')
    passwordForm.captcha = ''
    userCaptchaUrl.value = captchaUrl('user')
  } finally {
    pending.password = false
  }
}

function openForgetDialog() {
  forgetConfirmation.value = ''
  forgetDialogOpen.value = true
}

function handleForgetDialogClosed() {
  if (!pending.forget) forgetConfirmation.value = ''
}

async function forgetMe() {
  if (!canForget.value || pending.forget) return

  pending.forget = true
  try {
    await userApi.forgetMe()
    allowRouteLeave.value = true
    forgetConfirmation.value = ''
    forgetDialogOpen.value = false
    notify.success(t('profile.forgotten'), { key: 'profile:privacy:forgotten' })
    try {
      await auth.logout()
    } catch {
      auth.clearLocalSession()
      await router.replace('/login')
    }
  } catch (caught) {
    notify.fromApiError(caught, 'profile.forgetFailed')
  } finally {
    pending.forget = false
  }
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!profileDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(async (to) => {
  if (allowRouteLeave.value || to.path === '/login' || !profileDirty.value) return true
  return confirmDiscardChanges()
})

onMounted(() => {
  void loadAccount()
  window.addEventListener('beforeunload', handleBeforeUnload)
  loadCaptchaConfig()
    .then((config) => {
      captchaConfig.value = config
    })
    .catch(() => {
      captchaConfig.value = { provider: 'local', siteKey: '' }
    })
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
  profileResource.cancel()
  addressResource.cancel()
})
</script>

<template>
  <div class="route-view profile-page">
    <PageHeader
      data-account-section="overview"
      :title="$t('nav.profile')"
      :eyebrow="$t('profile.accountOverview')"
      :description="profileMeta"
    />

    <AsyncStateView
      :status="profileResource.status.value"
      :error="profileResource.error.value"
      @retry="loadAccount"
    >
      <section
        v-if="profile.passwordChangeRequired"
        class="required-password"
        data-account-section="required-password"
      >
        <el-alert
          role="alert"
          type="warning"
          show-icon
          :closable="false"
          :title="$t('auth.passwordUpdateRequiredTitle')"
        >
          <div class="required-alert__content">
            <p>{{ $t('auth.passwordUpdateRequiredDescription') }}</p>
            <el-button type="warning" plain @click="focusPasswordForm">
              {{ $t('auth.completePasswordUpdate') }}
            </el-button>
          </div>
        </el-alert>
      </section>

      <el-tabs v-model="activeTab" class="profile-tabs">
        <el-tab-pane
          :label="$t('profile.tabs.identity')"
          name="identity"
          :disabled="profile.passwordChangeRequired"
        >
          <section class="profile-section identity-section" data-account-section="identity">
            <div class="identity-summary">
              <img
                :src="profile.avatar || defaultAvatar"
                :alt="$t('auth.avatar')"
                @error="onAvatarError"
              />
              <div>
                <span>{{ $t('profile.memberAccount') }}</span>
                <h2>{{ profile.username || $t('nav.profile') }}</h2>
                <p>{{ profileMeta }}</p>
              </div>
            </div>

            <div class="identity-actions">
              <label
                class="file-picker"
                :class="{ 'is-disabled': pending.avatar || profile.passwordChangeRequired }"
                for="profile-avatar-input"
                :aria-disabled="pending.avatar || profile.passwordChangeRequired"
              >
                <el-icon aria-hidden="true"><Upload /></el-icon>
                <span>{{
                  pending.avatar ? $t('common.loading') : $t('profile.changeAvatar')
                }}</span>
                <input
                  id="profile-avatar-input"
                  type="file"
                  accept="image/png,image/jpeg"
                  :disabled="pending.avatar || profile.passwordChangeRequired"
                  @change="changeAvatar"
                />
              </label>
              <RouterLink class="secondary-button" to="/membership">
                {{ $t('profile.openMembership') }}
              </RouterLink>
            </div>
          </section>
        </el-tab-pane>

        <el-tab-pane
          :label="$t('profile.tabs.addresses')"
          name="addresses"
          :disabled="profile.passwordChangeRequired"
        >
          <section class="profile-section" data-account-section="addresses">
            <header class="section-heading">
              <div>
                <h2>
                  <el-icon aria-hidden="true"><Location /></el-icon>
                  {{ $t('profile.savedAddresses') }}
                </h2>
                <p>{{ $t('profile.addressesHint') }}</p>
              </div>
              <span>{{ $t('profile.addressCount', { count: addressCount }) }}</span>
            </header>

            <el-form
              ref="addressFormRef"
              :model="addressForm"
              :rules="addressRules"
              label-position="top"
              class="address-form"
              @submit.prevent="saveAddress"
            >
              <el-form-item :label="$t('common.receiver')" prop="receiverName">
                <el-input v-model="addressForm.receiverName" autocomplete="name" />
              </el-form-item>
              <el-form-item :label="$t('auth.phone')" prop="phone">
                <el-input v-model="addressForm.phone" autocomplete="tel" />
              </el-form-item>
              <el-form-item :label="$t('common.address')" prop="detailAddress">
                <el-input v-model="addressForm.detailAddress" autocomplete="street-address" />
              </el-form-item>
              <el-button
                type="primary"
                native-type="submit"
                :icon="Check"
                :loading="pending.addressCreate"
                :disabled="pending.addressCreate"
              >
                {{ $t('profile.addAddress') }}
              </el-button>
            </el-form>

            <AsyncStateView
              :status="addressResource.status.value"
              mode="grid"
              :error="addressResource.error.value"
              preserve-content-on-error
              @retry="loadAddresses()"
            >
              <template #empty>
                <p class="address-empty" role="status">{{ $t('profile.noAddresses') }}</p>
              </template>

              <div class="address-list">
                <article v-for="address in addresses" :key="address.id" class="address-item">
                  <div class="address-item__identity">
                    <strong>{{ maskName(address.receiverName) }}</strong>
                    <span>{{ maskPhone(address.phone) }}</span>
                  </div>
                  <p>{{ address.detailAddress }}</p>
                  <div class="address-item__default">
                    <span>{{ $t('common.default') }}</span>
                    <el-switch
                      :model-value="address.isDefault === 1"
                      :aria-label="`${$t('common.default')} ${maskName(address.receiverName)}`"
                      :loading="defaultAddressIds.has(address.id)"
                      :disabled="defaultAddressIds.has(address.id)"
                      @update:model-value="(value: boolean) => setDefaultAddress(address.id, value)"
                    />
                  </div>
                  <div class="address-item__actions">
                    <el-button
                      plain
                      :icon="Edit"
                      :data-address-edit="address.id"
                      :disabled="deletingAddressIds.has(address.id)"
                      @click="openEditDialog(address, $event)"
                    >
                      {{ $t('common.edit') }}
                    </el-button>
                    <el-button
                      type="danger"
                      plain
                      :loading="deletingAddressIds.has(address.id)"
                      :disabled="deletingAddressIds.has(address.id)"
                      @click="removeAddress(address.id)"
                    >
                      {{ $t('common.delete') }}
                    </el-button>
                  </div>
                </article>
              </div>

              <el-pagination
                v-if="addressCount > addressPageSize"
                class="profile-address-pagination"
                background
                layout="prev, pager, next, total"
                :current-page="addressPageNumber + 1"
                :page-size="addressPageSize"
                :total="addressCount"
                @current-change="changeAddressPage"
              />
            </AsyncStateView>
          </section>
        </el-tab-pane>

        <el-tab-pane :label="$t('profile.tabs.security')" name="security">
          <section class="profile-section" data-account-section="security">
            <header class="section-heading">
              <div>
                <h2>
                  <el-icon aria-hidden="true"><Lock /></el-icon>
                  {{ $t('profile.passwordSecurity') }}
                </h2>
                <p>{{ $t('profile.securityHint') }}</p>
              </div>
            </header>

            <el-form
              ref="passwordFormRef"
              :model="passwordForm"
              :rules="passwordRules"
              label-position="top"
              class="password-form"
              @submit.prevent="changePassword"
            >
              <el-form-item :label="$t('auth.oldPassword')" prop="oldPassword">
                <el-input
                  ref="currentPasswordInput"
                  v-model="passwordForm.oldPassword"
                  type="password"
                  autocomplete="current-password"
                  show-password
                />
              </el-form-item>
              <el-form-item :label="$t('auth.phone')" prop="phone">
                <el-input v-model="passwordForm.phone" autocomplete="tel" />
              </el-form-item>
              <el-form-item :label="$t('auth.newPassword')" prop="newPassword">
                <el-input
                  v-model="passwordForm.newPassword"
                  type="password"
                  autocomplete="new-password"
                  show-password
                />
              </el-form-item>
              <HumanVerification
                v-if="turnstileEnabled"
                v-model="passwordForm.captcha"
                action="change-password"
                :site-key="captchaConfig.siteKey"
              />
              <el-form-item v-else :label="$t('auth.captcha')" class="captcha-field">
                <div class="captcha-row">
                  <el-input v-model="passwordForm.captcha" :placeholder="$t('auth.captcha')" />
                  <button
                    class="captcha-image-button"
                    type="button"
                    :aria-label="$t('common.refreshCaptcha')"
                    @click="userCaptchaUrl = captchaUrl('user')"
                  >
                    <img :src="userCaptchaUrl" :alt="$t('auth.captchaImageAlt')" />
                  </button>
                  <el-button
                    :icon="Refresh"
                    circle
                    native-type="button"
                    :aria-label="$t('common.refreshCaptcha')"
                    @click="userCaptchaUrl = captchaUrl('user')"
                  />
                </div>
              </el-form-item>
              <p v-if="passwordFormError" class="form-error" role="alert">
                {{ passwordFormError }}
              </p>
              <el-button
                class="password-submit"
                type="primary"
                native-type="submit"
                :icon="Lock"
                :loading="pending.password"
                :disabled="pending.password"
              >
                {{ passwordButtonLabel }}
              </el-button>
            </el-form>
          </section>
        </el-tab-pane>

        <el-tab-pane
          :label="$t('profile.tabs.privacy')"
          name="privacy"
          :disabled="profile.passwordChangeRequired"
        >
          <section class="profile-section privacy-section" data-account-section="privacy">
            <header class="section-heading">
              <div>
                <h2>{{ $t('profile.privacyTitle') }}</h2>
                <p>{{ $t('profile.privacyHint') }}</p>
              </div>
            </header>

            <div class="privacy-layout">
              <div class="privacy-visual">
                <MascotState pose="shield" size="sm" :alt="$t('profile.privacyMascotAlt')" />
              </div>
              <div class="privacy-impact">
                <strong>{{ $t('profile.irreversibleTitle') }}</strong>
                <p>{{ $t('profile.irreversibleHint') }}</p>
                <ul>
                  <li>{{ $t('profile.forgetProfileImpact') }}</li>
                  <li>{{ $t('profile.forgetAddressImpact') }}</li>
                  <li>{{ $t('profile.forgetOrderImpact') }}</li>
                </ul>
                <el-button type="danger" plain @click="openForgetDialog">
                  {{ $t('profile.forgetMe') }}
                </el-button>
              </div>
            </div>
          </section>
        </el-tab-pane>
      </el-tabs>
    </AsyncStateView>

    <el-dialog
      v-model="editDialogOpen"
      :title="$t('common.edit')"
      width="min(480px, calc(100vw - 32px))"
      destroy-on-close
      :show-close="!pending.addressEdit"
      :close-on-click-modal="!pending.addressEdit"
      :close-on-press-escape="!pending.addressEdit"
      :before-close="beforeEditDialogClose"
      @closed="handleEditDialogClosed"
    >
      <el-form ref="editFormRef" :model="editForm" :rules="addressRules" label-position="top">
        <el-form-item :label="$t('common.receiver')" prop="receiverName">
          <el-input v-model="editForm.receiverName" autocomplete="name" />
        </el-form-item>
        <el-form-item :label="$t('auth.phone')" prop="phone">
          <el-input v-model="editForm.phone" autocomplete="tel" />
        </el-form-item>
        <el-form-item :label="$t('common.address')" prop="detailAddress">
          <el-input
            v-model="editForm.detailAddress"
            type="textarea"
            :rows="2"
            autocomplete="street-address"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="pending.addressEdit" @click="closeEditDialog">
          {{ $t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="pending.addressEdit"
          :disabled="pending.addressEdit"
          @click="submitEditAddress"
        >
          {{ $t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="forgetDialogOpen"
      :title="$t('profile.forgetDialogTitle')"
      width="min(520px, calc(100vw - 32px))"
      :show-close="!pending.forget"
      :close-on-click-modal="!pending.forget"
      :close-on-press-escape="!pending.forget"
      @closed="handleForgetDialogClosed"
    >
      <div class="forget-dialog-content">
        <p>{{ $t('profile.forgetDialogHint', { phrase: FORGET_PHRASE }) }}</p>
        <el-input
          v-model="forgetConfirmation"
          :aria-label="$t('profile.forgetInputLabel', { phrase: FORGET_PHRASE })"
          :placeholder="FORGET_PHRASE"
          autocomplete="off"
          :disabled="pending.forget"
        />
      </div>
      <template #footer>
        <el-button :disabled="pending.forget" @click="forgetDialogOpen = false">
          {{ $t('common.cancel') }}
        </el-button>
        <el-button
          type="danger"
          :loading="pending.forget"
          :disabled="pending.forget || !canForget"
          @click="forgetMe"
        >
          {{ $t('profile.forgetConfirm') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.profile-page,
.profile-page :deep(.async-state-view__content),
.profile-section,
.section-heading > div,
.identity-summary > div,
.address-list,
.address-item__identity,
.privacy-layout,
.privacy-visual,
.privacy-impact,
.forget-dialog-content {
  display: grid;
}

.profile-page,
.profile-page :deep(.async-state-view__content) {
  gap: var(--space-5);
}

.required-password {
  padding-top: var(--space-1);
}

.required-alert__content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  margin-top: var(--space-2);
}

.required-alert__content p,
.section-heading h2,
.section-heading p,
.identity-summary h2,
.identity-summary p,
.address-item p,
.privacy-impact p,
.forget-dialog-content p {
  margin: 0;
}

.profile-tabs {
  border-top: 1px solid var(--color-line);
  padding-top: var(--space-3);
}

.profile-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--space-5);
}

.profile-tabs :deep(.el-tabs__item) {
  min-height: 44px;
}

.profile-section {
  gap: var(--space-5);
  min-width: 0;
  padding-bottom: var(--space-5);
}

.identity-section {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
}

.identity-summary {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  min-width: 0;
}

.identity-summary img {
  width: 96px;
  height: 96px;
  border: 1px solid var(--color-line);
  border: 3px solid var(--color-surface);
  border-radius: var(--radius-circle);
  box-shadow:
    0 0 0 1px var(--color-line-strong),
    var(--shadow-surface);
  object-fit: cover;
}

.identity-summary > div {
  gap: var(--space-1);
  min-width: 0;
}

.identity-summary span,
.identity-summary p,
.section-heading p,
.section-heading > span,
.address-item__identity span,
.address-item__default span,
.privacy-impact p,
.privacy-impact li,
.forget-dialog-content p {
  color: var(--color-text-muted);
  font-size: var(--text-sm);
}

.identity-summary h2 {
  overflow-wrap: anywhere;
  font-size: var(--text-xl);
}

.identity-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--space-2);
}

.file-picker {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  min-height: 40px;
  padding: 0 var(--space-4);
  border: 1px solid var(--color-line-strong);
  border-radius: var(--radius-control);
  cursor: pointer;
  font-weight: 700;
}

.file-picker.is-disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.file-picker:focus-within {
  outline: 2px solid var(--color-brand);
  outline-offset: 2px;
}

.file-picker input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.section-heading > div {
  gap: var(--space-1);
}

.section-heading h2 {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--text-lg);
}

.address-form,
.password-form {
  display: grid;
  align-items: end;
  gap: var(--space-3);
}

.address-form {
  grid-template-columns: repeat(3, minmax(0, 1fr)) auto;
  padding-bottom: var(--space-5);
  border-bottom: 1px solid var(--color-line);
}

.password-form {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.address-form :deep(.el-form-item),
.password-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.address-form > .el-button,
.password-submit {
  min-height: 40px;
}

.address-list {
  grid-template-columns: repeat(auto-fill, minmax(280px, 520px));
  gap: var(--space-3);
}

.profile-address-pagination {
  justify-self: center;
}

.address-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-3);
  min-width: 0;
  padding: var(--space-4);
  border: 1px solid var(--color-line);
  border-top: 3px solid var(--color-brand);
  border-radius: var(--radius-surface);
  background: var(--color-surface-raised);
  box-shadow: var(--shadow-surface);
}

.address-item__identity {
  gap: var(--space-1);
}

.address-item p {
  grid-column: 1 / -1;
  overflow-wrap: anywhere;
  line-height: 1.6;
}

.address-item__default,
.address-item__actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.address-item__actions {
  grid-column: 1 / -1;
  justify-content: flex-end;
  padding-top: var(--space-2);
  border-top: 1px solid var(--color-line);
}

.address-empty {
  min-height: 160px;
  margin: 0;
  align-content: center;
  color: var(--color-text-muted);
  text-align: center;
}

.captcha-field,
.form-error {
  grid-column: 1 / -1;
}

.captcha-row {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  width: 100%;
}

.captcha-row :deep(.el-input) {
  max-width: 220px;
}

.captcha-image-button {
  width: 120px;
  height: 40px;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--color-line);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  cursor: pointer;
}

.captcha-image-button img {
  display: block;
  width: 120px;
  height: 40px;
  object-fit: cover;
}

.form-error {
  margin: 0;
  color: var(--color-danger);
  font-size: var(--text-sm);
}

.password-submit {
  width: fit-content;
}

.privacy-section {
  max-width: 920px;
}

.privacy-layout {
  grid-template-columns: 176px minmax(0, 1fr);
  align-items: center;
  gap: var(--space-6);
  padding: var(--space-5) var(--space-6);
  border-block: 1px solid var(--color-line);
  background: var(--color-brand-soft);
}

.privacy-visual {
  place-items: center;
  min-width: 0;
  border-right: 1px solid var(--color-line-strong);
}

.privacy-visual :deep(.mascot-state) {
  filter: drop-shadow(0 8px 12px color-mix(in srgb, var(--color-text) 14%, transparent));
}

.privacy-impact {
  gap: var(--space-3);
}

.privacy-layout .privacy-impact p,
.privacy-layout .privacy-impact li {
  color: var(--color-text);
}

.privacy-impact ul {
  display: grid;
  gap: var(--space-2);
  margin: 0;
  padding-left: var(--space-5);
}

.privacy-impact .el-button {
  justify-self: start;
  min-height: 40px;
}

.forget-dialog-content {
  gap: var(--space-4);
}

@media (max-width: 900px) {
  .identity-section,
  .address-form,
  .password-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .identity-actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }

  .address-form > .el-button,
  .captcha-field,
  .form-error {
    grid-column: 1 / -1;
  }

  .privacy-layout {
    grid-template-columns: 128px minmax(0, 1fr);
    padding-inline: var(--space-5);
  }
}

@media (max-width: 640px) {
  .required-alert__content,
  .identity-summary,
  .section-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .profile-tabs :deep(.el-tabs__nav-wrap) {
    overflow-x: auto;
  }

  .profile-tabs :deep(.el-tabs__nav) {
    float: none;
    min-width: max-content;
  }

  .identity-section,
  .address-form,
  .password-form,
  .privacy-layout {
    grid-template-columns: 1fr;
  }

  .privacy-layout {
    gap: var(--space-3);
    padding: var(--space-4);
  }

  .privacy-visual {
    justify-items: start;
    border-right: 0;
    border-bottom: 1px solid var(--color-line-strong);
  }

  .privacy-visual :deep(.mascot-state) {
    --mascot-size: 104px;
  }

  .identity-summary img {
    width: 80px;
    height: 80px;
  }

  .identity-actions,
  .address-item__actions,
  .captcha-row {
    flex-wrap: wrap;
  }

  .identity-actions > *,
  .identity-actions .secondary-button,
  .address-form > .el-button,
  .address-item__actions > *,
  .password-submit,
  .required-alert__content :deep(.el-button) {
    width: 100%;
    min-height: 44px;
  }
}
</style>

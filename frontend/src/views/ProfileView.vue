<script setup lang="ts">
import { Check, Edit, Lock, Location, Refresh, Upload } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { captchaConfig as loadCaptchaConfig, captchaUrl } from '@/api/auth'
import { uploadImage } from '@/api/catalog'
import * as userApi from '@/api/user'
import HumanVerification from '@/components/HumanVerification.vue'
import AsyncStateView from '@/components/ui/AsyncStateView.vue'
import DataTableShell from '@/components/ui/DataTableShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { useAsyncState } from '@/composables/useAsyncState'
import { useNotify } from '@/composables/useNotify'
import { useAuthStore } from '@/stores/auth'
import type { Address, CaptchaConfig, UserProfile } from '@/types'

interface AccountData {
  profile: UserProfile
  addresses: Address[]
}

const defaultAvatar = '/images/default_avatar.png'
const userCaptchaUrl = ref(captchaUrl('user'))
const captchaConfig = ref<CaptchaConfig>({ provider: 'local', siteKey: '' })
const turnstileEnabled = computed(() => captchaConfig.value.provider === 'turnstile')
const addressForm = reactive({ receiverName: '', phone: '', detailAddress: '' })
const passwordForm = reactive({ oldPassword: '', phone: '', newPassword: '', captcha: '' })
const editForm = reactive({ receiverName: '', phone: '', detailAddress: '' })
const addressFormRef = ref<FormInstance>()
const passwordFormRef = ref<FormInstance>()
const editFormRef = ref<FormInstance>()
const currentPasswordInput = ref<{ focus: () => void }>()
const passwordFormError = ref('')
const editDialogOpen = ref(false)
const editingAddressId = ref<number | null>(null)
const editTrigger = ref<HTMLElement | null>(null)
const pending = reactive({
  avatar: false,
  password: false,
  addressCreate: false,
  addressEdit: false,
})
const deletingAddressIds = reactive(new Set<number>())
const defaultAddressIds = reactive(new Set<number>())
const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const notify = useNotify()
const { data: account, status, error, load } = useAsyncState<AccountData>({ timeoutMs: 20000 })

const profile = computed<UserProfile>(() => account.value?.profile ?? {})
const addresses = computed<Address[]>(() => account.value?.addresses ?? [])
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
  if (profile.value.maskedPhone === 'not bound') {
    return t('auth.phoneNotBound')
  }
  return profile.value.maskedPhone
})
const profileMeta = computed(() => {
  const identityLabel =
    profile.value.identity === 'ADMIN'
      ? t('nav.admin')
      : profile.value.identity === 'USER'
        ? t('nav.profile')
        : profile.value.identity
  const meta = [identityLabel, phoneLabel.value].filter(Boolean)
  return meta.length ? meta.join(' / ') : t('profile.accountPending')
})
const passwordButtonLabel = computed(() =>
  profile.value.passwordChangeRequired
    ? t('auth.completePasswordUpdate')
    : t('auth.updatePassword'),
)

function maskName(value: string): string {
  const characters = Array.from(value.trim())
  if (characters.length === 0) {
    return ''
  }
  if (characters.length === 1) {
    return '*'
  }
  return `${characters[0]}${'*'.repeat(Math.min(3, characters.length - 1))}`
}

function maskPhone(value: string): string {
  const normalized = value.trim()
  if (normalized.length <= 7) {
    return '*'.repeat(Math.max(1, normalized.length))
  }
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
  if (!form) {
    return false
  }
  return form.validate().then(
    () => true,
    () => false,
  )
}

async function loadAccount() {
  await load(async () => {
    const nextProfile = await userApi.profile()
    const nextAddresses = nextProfile.passwordChangeRequired ? [] : await userApi.addresses()
    return { profile: nextProfile, addresses: nextAddresses }
  })
}

async function refreshAddresses() {
  if (!account.value) {
    return
  }
  const nextAddresses = await userApi.addresses()
  account.value = { ...account.value, addresses: nextAddresses }
}

async function changeAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || pending.avatar || profile.value.passwordChangeRequired) {
    return
  }

  pending.avatar = true
  try {
    const uploaded = await uploadImage(file, 'avatar')
    await userApi.updateAvatar(uploaded.path)
    notify.success(t('auth.avatarUpdated'))
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
  editForm.receiverName = address.receiverName
  editForm.phone = address.phone
  editForm.detailAddress = address.detailAddress
  editTrigger.value = event.currentTarget as HTMLElement
  editDialogOpen.value = true
  void nextTick(() => editFormRef.value?.clearValidate())
}

function handleEditDialogClosed() {
  const addressId = editingAddressId.value
  const fallback = editTrigger.value
  editingAddressId.value = null
  editTrigger.value = null
  editFormRef.value?.clearValidate()
  void nextTick(() => {
    const selector = addressId === null ? '' : `[data-address-edit="${addressId}"]`
    const target = selector ? document.querySelector<HTMLElement>(selector) : null
    ;(target ?? fallback)?.focus()
  })
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
    notify.success(t('common.updated'))
    editDialogOpen.value = false
  } catch (caught) {
    notify.fromApiError(caught, 'common.operationFailed')
  } finally {
    pending.addressEdit = false
  }
}

async function saveAddress() {
  if (pending.addressCreate || !(await validateForm(addressFormRef.value))) {
    return
  }

  pending.addressCreate = true
  try {
    await userApi.addAddress({ ...addressForm })
    await refreshAddresses()
    notify.success(t('common.updated'))
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
  if (!confirmed || deletingAddressIds.has(id)) {
    return
  }

  deletingAddressIds.add(id)
  try {
    await userApi.deleteAddress(id)
    await refreshAddresses()
    notify.success(t('common.updated'))
  } catch (caught) {
    notify.fromApiError(caught, 'common.operationFailed')
  } finally {
    deletingAddressIds.delete(id)
  }
}

async function setDefaultAddress(id: number, enabled: boolean) {
  if (!enabled || defaultAddressIds.has(id)) {
    return
  }

  defaultAddressIds.add(id)
  try {
    await userApi.setDefaultAddress(id)
    await refreshAddresses()
    notify.success(t('common.updated'))
  } catch (caught) {
    notify.fromApiError(caught, 'common.operationFailed')
  } finally {
    defaultAddressIds.delete(id)
  }
}

function focusPasswordForm() {
  void nextTick(() => currentPasswordInput.value?.focus())
}

async function changePassword() {
  passwordFormError.value = ''
  if (pending.password || !(await validateForm(passwordFormRef.value))) {
    return
  }
  if (!passwordForm.captcha.trim()) {
    passwordFormError.value = t('auth.captchaRequired')
    return
  }

  pending.password = true
  try {
    await userApi.updatePassword({ ...passwordForm })
    notify.success(t('auth.passwordChanged'))
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

onMounted(() => {
  void loadAccount()
  loadCaptchaConfig()
    .then((config) => {
      captchaConfig.value = config
    })
    .catch(() => {
      captchaConfig.value = { provider: 'local', siteKey: '' }
    })
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

    <AsyncStateView :status="status" :error="error" @retry="loadAccount">
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

      <section class="task-section account-task" data-account-section="avatar">
        <div class="account-details">
          <img
            :src="profile.avatar || defaultAvatar"
            :alt="$t('auth.avatar')"
            @error="onAvatarError"
          />
          <div>
            <h2>{{ profile.username || $t('nav.profile') }}</h2>
            <p>{{ profileMeta }}</p>
          </div>
        </div>
        <div class="account-actions">
          <label
            class="file-picker"
            :class="{ 'is-disabled': pending.avatar || profile.passwordChangeRequired }"
            for="profile-avatar-input"
            :aria-disabled="pending.avatar || profile.passwordChangeRequired"
          >
            <el-icon><Upload /></el-icon>
            <span>{{ pending.avatar ? $t('common.loading') : $t('common.upload') }}</span>
            <input
              id="profile-avatar-input"
              type="file"
              accept="image/png,image/jpeg"
              :disabled="pending.avatar || profile.passwordChangeRequired"
              @change="changeAvatar"
            />
          </label>
          <RouterLink
            v-if="!profile.passwordChangeRequired"
            class="secondary-button"
            to="/membership"
          >
            {{ $t('nav.membership') }}
          </RouterLink>
        </div>
      </section>

      <section ref="passwordSection" class="task-section" data-account-section="password">
        <h2 class="section-heading">
          <el-icon><Lock /></el-icon>
          <span>{{ $t('common.security') }}</span>
        </h2>
        <el-form
          ref="passwordFormRef"
          :model="passwordForm"
          :rules="passwordRules"
          label-position="top"
          class="inline-form password-form"
          @submit.prevent="changePassword"
        >
          <el-form-item :label="$t('auth.oldPassword')" prop="oldPassword">
            <el-input
              ref="currentPasswordInput"
              v-model="passwordForm.oldPassword"
              :placeholder="$t('auth.oldPassword')"
              type="password"
              autocomplete="current-password"
              show-password
            />
          </el-form-item>
          <el-form-item :label="$t('auth.phone')" prop="phone">
            <el-input
              v-model="passwordForm.phone"
              :placeholder="$t('auth.phone')"
              autocomplete="tel"
            />
          </el-form-item>
          <el-form-item :label="$t('auth.newPassword')" prop="newPassword">
            <el-input
              v-model="passwordForm.newPassword"
              :placeholder="$t('auth.newPassword')"
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
                <img :src="userCaptchaUrl" alt="Captcha" />
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

      <section
        v-if="!profile.passwordChangeRequired"
        class="task-section"
        data-account-section="addresses"
      >
        <h2 class="section-heading">
          <el-icon><Location /></el-icon>
          <span>{{ $t('common.address') }}</span>
        </h2>
        <el-form
          ref="addressFormRef"
          :model="addressForm"
          :rules="addressRules"
          label-position="top"
          class="inline-form address-form"
          @submit.prevent="saveAddress"
        >
          <el-form-item :label="$t('common.receiver')" prop="receiverName">
            <el-input
              v-model="addressForm.receiverName"
              :placeholder="$t('common.receiver')"
              autocomplete="name"
            />
          </el-form-item>
          <el-form-item :label="$t('auth.phone')" prop="phone">
            <el-input
              v-model="addressForm.phone"
              :placeholder="$t('auth.phone')"
              autocomplete="tel"
            />
          </el-form-item>
          <el-form-item :label="$t('common.address')" prop="detailAddress">
            <el-input
              v-model="addressForm.detailAddress"
              :placeholder="$t('common.address')"
              autocomplete="street-address"
            />
          </el-form-item>
          <el-button
            type="primary"
            native-type="submit"
            :icon="Check"
            :loading="pending.addressCreate"
            :disabled="pending.addressCreate"
          >
            {{ $t('common.save') }}
          </el-button>
        </el-form>

        <DataTableShell
          :aria-label="$t('common.address')"
          :empty="addresses.length === 0"
          :busy="defaultAddressIds.size > 0 || deletingAddressIds.size > 0"
        >
          <template #empty>{{ $t('profile.noAddresses') }}</template>
          <el-table :data="addresses" class="data-table" row-key="id">
            <el-table-column :label="$t('common.receiver')" min-width="140">
              <template #default="{ row }">{{ maskName(row.receiverName) }}</template>
            </el-table-column>
            <el-table-column :label="$t('auth.phone')" min-width="160">
              <template #default="{ row }">{{ maskPhone(row.phone) }}</template>
            </el-table-column>
            <el-table-column prop="detailAddress" :label="$t('common.address')" min-width="220" />
            <el-table-column :label="$t('common.default')" width="120">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.isDefault === 1"
                  :loading="defaultAddressIds.has(row.id)"
                  :disabled="defaultAddressIds.has(row.id)"
                  @update:model-value="(value: boolean) => setDefaultAddress(row.id, value)"
                />
              </template>
            </el-table-column>
            <el-table-column :label="$t('common.edit')" width="220" fixed="right">
              <template #default="{ row }">
                <el-button
                  plain
                  :icon="Edit"
                  :data-address-edit="row.id"
                  :disabled="deletingAddressIds.has(row.id)"
                  @click="openEditDialog(row, $event)"
                >
                  {{ $t('common.edit') }}
                </el-button>
                <el-button
                  type="danger"
                  plain
                  :loading="deletingAddressIds.has(row.id)"
                  :disabled="deletingAddressIds.has(row.id)"
                  @click="removeAddress(row.id)"
                >
                  {{ $t('common.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </DataTableShell>
      </section>
    </AsyncStateView>

    <el-dialog
      v-model="editDialogOpen"
      :title="$t('common.edit')"
      width="min(480px, calc(100vw - 32px))"
      destroy-on-close
      :show-close="!pending.addressEdit"
      :close-on-click-modal="!pending.addressEdit"
      :close-on-press-escape="!pending.addressEdit"
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
        <el-button :disabled="pending.addressEdit" @click="editDialogOpen = false">
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
  </div>
</template>

<style scoped>
.profile-page {
  display: grid;
  gap: 0;
}

.profile-page :deep(.async-state-view__content) {
  display: grid;
}

.required-password {
  padding: 4px 0 20px;
}

.required-alert__content {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.required-alert__content p {
  margin: 0;
}

.task-section {
  display: grid;
  gap: 18px;
  padding: 24px 0;
  border-bottom: 1px solid var(--color-border);
}

.task-section:last-child {
  border-bottom: 0;
}

.account-task {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
}

.account-details,
.account-actions,
.section-heading,
.captcha-row {
  display: flex;
  gap: 14px;
  align-items: center;
}

.account-details img {
  width: 88px;
  height: 88px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-surface);
  object-fit: cover;
}

.account-details h2,
.account-details p,
.section-heading {
  margin: 0;
}

.account-details p {
  margin-top: 4px;
  color: var(--text-muted);
}

.section-heading {
  font-size: 1.1rem;
}

.file-picker {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  min-height: 40px;
  padding: 0 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  cursor: pointer;
}

.file-picker.is-disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.file-picker input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

.inline-form {
  display: grid;
  gap: 14px;
  align-items: end;
}

.password-form {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.address-form {
  grid-template-columns: repeat(3, minmax(0, 1fr)) auto;
}

.inline-form :deep(.el-form-item) {
  margin-bottom: 0;
}

.captcha-field,
.form-error {
  grid-column: 1 / -1;
}

.captcha-row {
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
  border: 1px solid var(--color-border);
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
  font-size: 0.875rem;
}

.password-submit {
  width: fit-content;
}

.data-table {
  width: 100%;
}

@media (max-width: 900px) {
  .password-form,
  .address-form {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .required-alert__content,
  .account-task,
  .account-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .account-task,
  .password-form,
  .address-form {
    grid-template-columns: minmax(0, 1fr);
  }

  .account-actions {
    display: flex;
  }

  .file-picker,
  .account-actions .secondary-button,
  .inline-form :deep(.el-button),
  .required-alert__content :deep(.el-button) {
    min-height: 44px;
  }

  .captcha-row {
    flex-wrap: wrap;
  }
}
</style>

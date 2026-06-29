export type Role = 'ADMIN' | 'USER' | string

export interface ApiResult<T> {
  code: string
  message: string
  data: T
  traceId?: string
}

export interface ProblemDetail {
  title?: string
  detail?: string
  status?: number
  traceId?: string
}

export interface UserProfile {
  isLogin?: boolean
  identity?: Role
  username?: string
  avatar?: string
  maskedPhone?: string
  passwordChangeRequired?: boolean
}

export interface AvatarUpdateRequest {
  avatarPath: string
}

export interface LoginRequest {
  username: string
  password: string
  captcha?: string
  totp?: string
}

export interface LoginResponse {
  role: Role
  passwordChangeRequired: boolean
}

export interface CaptchaConfig {
  provider: 'local' | 'turnstile' | string
  siteKey?: string
}

export interface RegisterRequest {
  username: string
  password: string
  phone: string
  email?: string
  captcha: string
  avatarFile?: File | null
}

export interface PasswordResetChallenge {
  username: string
  phone: string
  email?: string
  captcha?: string
}

export interface PasswordResetRequest extends PasswordResetChallenge {
  otp: string
  emailToken?: string
  newPassword: string
}

export interface Monkey {
  id: number
  name: string
  breed: string
  price: string | number
  description?: string
  imageUrl: string
  stock: number
}

export interface MonkeyRequest {
  id?: number | null
  name: string
  breed: string
  price: string | number
  description?: string
  imageUrl: string
  stock: number
}

export interface Address {
  id: number
  receiverName: string
  phone: string
  detailAddress: string
  isDefault: number
}

export type AddressRequest = Pick<Address, 'receiverName' | 'phone' | 'detailAddress'>

export interface Order {
  id: number
  orderNo: string
  userId: number
  buyerName: string
  buyerAvatar?: string
  productId: number
  productName: string
  productImage: string
  price: string | number
  description?: string
  receiverName: string
  receiverPhone: string
  addressSnapshot: string
  shippingTime?: string
  status: string
  createTime: string
}

export interface Stats {
  totalGmv: string
  totalOrders: number
  totalVisits: number
  returnRate: string
  xAxis: string[]
  seriesOrder: number[]
  seriesGmv: Array<string | number>
  seriesVisit: number[]
}

export interface UploadResponse {
  path: string
  cropped: boolean
  variants: Record<string, string>
}

export type ToastKind = 'success' | 'warning' | 'error' | 'info'

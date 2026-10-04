import { request } from './http'
import type { ApiId } from './ids'
import type {
  Cart,
  CartAddItemRequest,
  CartCheckout,
  CartCheckoutRequest,
  CartSubOrder,
  CartSelectItemRequest,
  CartUpdateItemRequest,
} from '@/types'

export interface CartCheckoutSubOrderResult extends CartSubOrder {
  storeDiscountAmount: string | number
  platformDiscountAmount: string | number
  formalOrderId?: ApiId
}

export interface CartCheckoutResult extends Omit<CartCheckout, 'subOrders'> {
  subOrders: CartCheckoutSubOrderResult[]
  orderIds: ApiId[]
}

export interface CartDirectCheckoutRequest extends CartCheckoutRequest {
  skuId: ApiId
  shopId: ApiId
  quantity: number
}

export function getCart(): Promise<Cart> {
  return request<Cart>({ url: '/cart' })
}

export function addCartItem(requestBody: CartAddItemRequest): Promise<Cart> {
  return request<Cart>({
    url: '/cart/items',
    method: 'POST',
    data: requestBody,
  })
}

export function updateCartItem(skuId: ApiId, requestBody: CartUpdateItemRequest): Promise<Cart> {
  return request<Cart>({
    url: `/cart/items/${skuId}`,
    method: 'PATCH',
    data: requestBody,
  })
}

export function selectCartItem(skuId: ApiId, requestBody: CartSelectItemRequest): Promise<Cart> {
  return request<Cart>({
    url: `/cart/items/${skuId}/select`,
    method: 'POST',
    data: requestBody,
  })
}

export function removeCartItem(skuId: ApiId): Promise<Cart> {
  return request<Cart>({
    url: `/cart/items/${skuId}`,
    method: 'DELETE',
  })
}

export function previewCartCheckout(requestBody: CartCheckoutRequest): Promise<CartCheckoutResult> {
  return request<CartCheckoutResult>({
    url: '/cart/checkout/preview',
    method: 'POST',
    data: requestBody,
  })
}

export function checkoutCart(
  requestBody: CartCheckoutRequest,
  idempotencyKey: string,
): Promise<CartCheckoutResult> {
  return request<CartCheckoutResult>({
    url: '/cart/checkout',
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    data: requestBody,
  })
}

export function directCheckoutCart(
  requestBody: CartDirectCheckoutRequest,
  idempotencyKey: string,
): Promise<CartCheckoutResult> {
  return request<CartCheckoutResult>({
    url: '/cart/checkout/direct',
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    data: requestBody,
  })
}

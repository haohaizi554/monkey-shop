import { request } from './http'
import type {
  Cart,
  CartAddItemRequest,
  CartCheckout,
  CartCheckoutRequest,
  CartSelectItemRequest,
  CartUpdateItemRequest,
} from '@/types'

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

export function updateCartItem(skuId: number, requestBody: CartUpdateItemRequest): Promise<Cart> {
  return request<Cart>({
    url: `/cart/items/${skuId}`,
    method: 'PATCH',
    data: requestBody,
  })
}

export function selectCartItem(skuId: number, requestBody: CartSelectItemRequest): Promise<Cart> {
  return request<Cart>({
    url: `/cart/items/${skuId}/select`,
    method: 'POST',
    data: requestBody,
  })
}

export function removeCartItem(skuId: number): Promise<Cart> {
  return request<Cart>({
    url: `/cart/items/${skuId}`,
    method: 'DELETE',
  })
}

export function previewCartCheckout(requestBody: CartCheckoutRequest): Promise<CartCheckout> {
  return request<CartCheckout>({
    url: '/cart/checkout/preview',
    method: 'POST',
    data: requestBody,
  })
}

export function checkoutCart(
  requestBody: CartCheckoutRequest,
  idempotencyKey: string,
): Promise<CartCheckout> {
  return request<CartCheckout>({
    url: '/cart/checkout',
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
    data: requestBody,
  })
}

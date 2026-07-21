import type { OrderLineSummary, OrderShipmentLinePayload, OrderSummary } from '@/api/orders'

export function orderDisplayLines(order: OrderSummary): OrderLineSummary[] {
  if (order.lines?.length) {
    return order.lines
  }
  return [
    {
      skuId: order.productId,
      shopId: order.shopId,
      productName: order.productName,
      productImage: order.productImage,
      quantity: 1,
      unitPrice: order.price,
      originalAmount: order.originalAmount ?? order.price,
      discountAmount: order.discountAmount ?? 0,
      payableAmount: order.price,
      couponCodes: [],
    },
  ]
}

export function shipmentLinesForOrder(order: OrderSummary): OrderShipmentLinePayload[] {
  return orderDisplayLines(order).map((line) => ({
    skuId: line.skuId,
    productName: line.productName,
    quantity: line.quantity,
    orderedQuantity: line.quantity,
  }))
}

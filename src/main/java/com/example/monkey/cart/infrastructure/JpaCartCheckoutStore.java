package com.example.monkey.cart.infrastructure;

import com.example.monkey.cart.domain.CartCheckoutStore;
import com.example.monkey.cart.domain.CheckoutLine;
import com.example.monkey.cart.domain.CheckoutOrder;
import com.example.monkey.cart.domain.CheckoutSubOrder;
import com.example.monkey.shared.application.storage.ImageCleanupService;
import com.example.monkey.shared.application.storage.ImageReferenceTransactions;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(name = "app.cart.checkout-store.provider", havingValue = "jpa", matchIfMissing = true)
public class JpaCartCheckoutStore implements CartCheckoutStore {

    private static final String IMAGE_REFERENCE_CONFIGURATION_ERROR =
            "Image reference services are required for trackable image writes";

    private final CartCheckoutRepository checkoutRepository;
    private final CartSubOrderRepository subOrderRepository;
    private final CartCheckoutLineRepository lineRepository;
    private final ImageReferenceService imageReferenceService;
    private final ImageCleanupService imageCleanupService;

    @Autowired
    public JpaCartCheckoutStore(
            CartCheckoutRepository checkoutRepository,
            CartSubOrderRepository subOrderRepository,
            CartCheckoutLineRepository lineRepository,
            ImageReferenceService imageReferenceService,
            ImageCleanupService imageCleanupService) {
        this.checkoutRepository = checkoutRepository;
        this.subOrderRepository = subOrderRepository;
        this.lineRepository = lineRepository;
        this.imageReferenceService = imageReferenceService;
        this.imageCleanupService = imageCleanupService;
    }

    /** Compatibility constructor for direct mapping tests that do not execute production persistence. */
    public JpaCartCheckoutStore(
            CartCheckoutRepository checkoutRepository,
            CartSubOrderRepository subOrderRepository,
            CartCheckoutLineRepository lineRepository) {
        this(checkoutRepository, subOrderRepository, lineRepository, null, null);
    }

    @Override
    public Optional<CheckoutOrder> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey) {
        return checkoutRepository
                .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(this::toDomain);
    }

    @Override
    public CheckoutOrder save(CheckoutOrder checkout) {
        validateImageTracking(checkout);
        CartCheckoutEntity entity = checkoutRepository.findById(checkout.id()).orElseGet(CartCheckoutEntity::new);
        CartCheckoutEntity savedCheckout = checkoutRepository.save(updateEntity(entity, checkout));
        for (CheckoutSubOrder subOrder : checkout.subOrders()) {
            subOrderRepository.save(toEntity(savedCheckout.getId(), checkout.createdAt(), subOrder));
            for (CheckoutLine line : subOrder.lines()) {
                saveLine(savedCheckout.getId(), subOrder.id(), checkout.createdAt(), line);
            }
        }
        return toDomain(savedCheckout);
    }

    private void saveLine(
            Long checkoutId, Long subOrderId, java.time.LocalDateTime createdAt, CheckoutLine line) {
        CartCheckoutLineEntity existing = lineRepository.findById(line.id()).orElse(null);
        String oldImage = existing == null ? null : existing.getProductImage();
        String newImage = line.productImage();
        requireImageTrackingConfigured(oldImage, newImage);
        boolean imageChanged = !Objects.equals(oldImage, newImage);
        if (imageReferenceService != null && imageChanged) {
            ImageReferenceTransactions.retainBeforeWrite(imageReferenceService, newImage);
        }
        lineRepository.save(toEntity(checkoutId, subOrderId, createdAt, line));
        if (imageReferenceService != null && imageChanged && oldImage != null) {
            ImageReferenceTransactions.releaseAfterCommit(imageReferenceService, imageCleanupService, oldImage);
        }
    }

    private void validateImageTracking(CheckoutOrder checkout) {
        if (imageReferenceService != null && imageCleanupService != null) {
            return;
        }
        for (CheckoutSubOrder subOrder : checkout.subOrders()) {
            for (CheckoutLine line : subOrder.lines()) {
                if (ImageReferenceService.isTrackable(line.productImage())) {
                    throw missingImageReferenceServices();
                }
                CartCheckoutLineEntity existing = lineRepository.findById(line.id()).orElse(null);
                if (existing != null && ImageReferenceService.isTrackable(existing.getProductImage())) {
                    throw missingImageReferenceServices();
                }
            }
        }
    }

    private void requireImageTrackingConfigured(String oldImage, String newImage) {
        if ((ImageReferenceService.isTrackable(oldImage) || ImageReferenceService.isTrackable(newImage))
                && (imageReferenceService == null || imageCleanupService == null)) {
            throw missingImageReferenceServices();
        }
    }

    private static IllegalStateException missingImageReferenceServices() {
        return new IllegalStateException(IMAGE_REFERENCE_CONFIGURATION_ERROR);
    }

    private CheckoutOrder toDomain(CartCheckoutEntity checkout) {
        List<CartSubOrderEntity> subOrders = subOrderRepository.findByCheckoutIdOrderByIdAsc(checkout.getId());
        Map<Long, List<CartCheckoutLineEntity>> linesBySubOrder =
                lineRepository.findByCheckoutIdOrderBySubOrderIdAscIdAsc(checkout.getId()).stream()
                        .collect(Collectors.groupingBy(CartCheckoutLineEntity::getSubOrderId, Collectors.toList()));
        List<CheckoutSubOrder> domainSubOrders = subOrders.stream()
                .map(subOrder -> toSubOrder(subOrder, linesForSubOrder(subOrder.getId(), linesBySubOrder)))
                .toList();
        return new CheckoutOrder(
                checkout.getId(),
                checkout.getCheckoutNo(),
                checkout.getUserId(),
                checkout.getAddressId(),
                checkout.getIdempotencyKey(),
                checkout.getRequestFingerprint(),
                checkout.getOriginalAmount(),
                checkout.getDiscountAmount(),
                checkout.getPayableAmount(),
                checkout.getStatus(),
                checkout.getProvince(),
                checkout.getCreateTime(),
                domainSubOrders);
    }

    private static List<CheckoutLine> linesForSubOrder(
            Long subOrderId, Map<Long, List<CartCheckoutLineEntity>> linesBySubOrder) {
        return linesBySubOrder.getOrDefault(subOrderId, List.of()).stream()
                .map(JpaCartCheckoutStore::toLine)
                .toList();
    }

    private static CheckoutSubOrder toSubOrder(CartSubOrderEntity subOrder, List<CheckoutLine> lines) {
        return new CheckoutSubOrder(
                subOrder.getId(),
                subOrder.getShopId(),
                subOrder.getOrderNo(),
                subOrder.getOriginalAmount(),
                subOrder.getStoreDiscountAmount(),
                subOrder.getPlatformDiscountAmount(),
                subOrder.getDiscountAmount(),
                subOrder.getPayableAmount(),
                subOrder.getFormalOrderId(),
                subOrder.getStatus(),
                lines);
    }

    private static CartCheckoutEntity updateEntity(CartCheckoutEntity entity, CheckoutOrder checkout) {
        entity.setId(checkout.id());
        entity.setCheckoutNo(checkout.checkoutNo());
        entity.setUserId(checkout.userId());
        entity.setAddressId(checkout.addressId());
        entity.setIdempotencyKey(checkout.idempotencyKey());
        entity.setRequestFingerprint(checkout.requestFingerprint());
        entity.setOriginalAmount(checkout.originalAmount());
        entity.setDiscountAmount(checkout.discountAmount());
        entity.setPayableAmount(checkout.payableAmount());
        entity.setStatus(checkout.status());
        entity.setProvince(checkout.province());
        entity.setCreateTime(checkout.createdAt());
        return entity;
    }

    private static CartSubOrderEntity toEntity(
            Long checkoutId, java.time.LocalDateTime createdAt, CheckoutSubOrder subOrder) {
        CartSubOrderEntity entity = new CartSubOrderEntity();
        entity.setId(subOrder.id());
        entity.setCheckoutId(checkoutId);
        entity.setOrderNo(subOrder.orderNo());
        entity.setShopId(subOrder.shopId());
        entity.setOriginalAmount(subOrder.originalAmount());
        entity.setStoreDiscountAmount(subOrder.storeDiscountAmount());
        entity.setPlatformDiscountAmount(subOrder.platformDiscountAmount());
        entity.setDiscountAmount(subOrder.discountAmount());
        entity.setPayableAmount(subOrder.payableAmount());
        entity.setFormalOrderId(subOrder.formalOrderId());
        entity.setStatus(subOrder.status());
        entity.setCreateTime(createdAt);
        return entity;
    }

    private static CartCheckoutLineEntity toEntity(
            Long checkoutId, Long subOrderId, java.time.LocalDateTime createdAt, CheckoutLine line) {
        CartCheckoutLineEntity entity = new CartCheckoutLineEntity();
        entity.setId(line.id());
        entity.setCheckoutId(checkoutId);
        entity.setSubOrderId(subOrderId);
        entity.setSkuId(line.skuId());
        entity.setShopId(line.shopId());
        entity.setCategoryId(line.categoryId());
        entity.setProductName(line.productName());
        entity.setProductImage(line.productImage());
        entity.setQuantity(line.quantity());
        entity.setUnitPrice(line.unitPrice());
        entity.setOriginalAmount(line.originalAmount());
        entity.setDiscountAmount(line.discountAmount());
        entity.setPayableAmount(line.payableAmount());
        entity.setCouponCodes(String.join(",", line.couponCodes()));
        entity.setReservationKey(line.reservationKey());
        entity.setWarehouseId(line.warehouseId());
        entity.setCreateTime(createdAt);
        return entity;
    }

    private static CheckoutLine toLine(CartCheckoutLineEntity entity) {
        return new CheckoutLine(
                entity.getId(),
                entity.getSkuId(),
                entity.getShopId(),
                entity.getCategoryId(),
                entity.getProductName(),
                entity.getProductImage(),
                entity.getQuantity(),
                entity.getUnitPrice(),
                entity.getOriginalAmount(),
                entity.getDiscountAmount(),
                entity.getPayableAmount(),
                splitCoupons(entity.getCouponCodes()),
                entity.getReservationKey(),
                entity.getWarehouseId());
    }

    private static List<String> splitCoupons(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).filter(StringUtils::hasText).toList();
    }
}

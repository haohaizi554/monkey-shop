package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.order.domain.OrderReview;
import com.example.monkey.order.domain.OrderShipmentBatch;
import com.example.monkey.order.domain.OrderShipmentLine;
import com.example.monkey.order.domain.OrderShipmentStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaOrderFulfillmentStoreTest {

    @Mock
    private OrderFulfillmentItemRepository itemRepository;

    @Mock
    private OrderShipmentBatchRepository shipmentRepository;

    @Mock
    private OrderShipmentLineRepository shipmentLineRepository;

    @Mock
    private OrderReviewRepository reviewRepository;

    private JpaOrderFulfillmentStore store;

    @BeforeEach
    void setUp() {
        store = new JpaOrderFulfillmentStore(
                itemRepository, shipmentRepository, shipmentLineRepository, reviewRepository);
    }

    @Test
    void saveShipmentPersistsBatchAndLinesWithSnowflakeIds() {
        OrderShipmentBatch shipment = shipment();

        OrderShipmentBatch saved = store.saveShipment(shipment);

        assertThat(saved).isEqualTo(shipment);
        OrderShipmentBatchEntity batch = captureSavedBatch();
        assertThat(batch.getId()).isEqualTo(200L);
        assertThat(batch.getStatus()).isEqualTo(OrderShipmentStatus.SHIPPED);
        OrderShipmentLineEntity line = captureSavedLine();
        assertThat(line.getShipmentId()).isEqualTo(200L);
        assertThat(line.getSkuId()).isEqualTo(7L);
    }

    @Test
    void findShipmentsMapsBatchWithLines() {
        when(shipmentRepository.findByOrderIdOrderByShippedAtAsc(10L)).thenReturn(List.of(batchEntity()));
        when(shipmentLineRepository.findByShipmentIdIn(List.of(200L))).thenReturn(List.of(lineEntity()));

        List<OrderShipmentBatch> result = store.findShipments(10L);

        assertThat(result).containsExactly(shipment());
    }

    @Test
    void saveAndFindReviewsPreservesImageList() {
        when(reviewRepository.save(any(OrderReviewEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewRepository.findByOrderIdOrderByCreateTimeDesc(10L)).thenReturn(List.of(reviewEntity()));

        OrderReview saved = store.saveReview(review());
        List<OrderReview> reviews = store.findReviews(10L);

        assertThat(saved).isEqualTo(review());
        assertThat(reviews).containsExactly(review());
    }

    private OrderShipmentBatchEntity captureSavedBatch() {
        ArgumentCaptor<OrderShipmentBatchEntity> captor = ArgumentCaptor.forClass(OrderShipmentBatchEntity.class);
        verify(shipmentRepository).save(captor.capture());
        return captor.getValue();
    }

    private OrderShipmentLineEntity captureSavedLine() {
        ArgumentCaptor<OrderShipmentLineEntity> captor = ArgumentCaptor.forClass(OrderShipmentLineEntity.class);
        verify(shipmentLineRepository).save(captor.capture());
        return captor.getValue();
    }

    private static OrderShipmentBatch shipment() {
        return new OrderShipmentBatch(
                200L,
                10L,
                "SHP200",
                "SF",
                "SF100",
                OrderShipmentStatus.SHIPPED,
                LocalDateTime.parse("2026-07-04T08:00:00"),
                null,
                List.of(new OrderShipmentLine(201L, 200L, 10L, 7L, "Momo", 1)));
    }

    private static OrderShipmentBatchEntity batchEntity() {
        OrderShipmentBatchEntity entity = new OrderShipmentBatchEntity();
        entity.setId(200L);
        entity.setOrderId(10L);
        entity.setShipmentNo("SHP200");
        entity.setCarrier("SF");
        entity.setTrackingNo("SF100");
        entity.setStatus(OrderShipmentStatus.SHIPPED);
        entity.setShippedAt(LocalDateTime.parse("2026-07-04T08:00:00"));
        entity.setCreateTime(LocalDateTime.parse("2026-07-04T08:00:00"));
        return entity;
    }

    private static OrderShipmentLineEntity lineEntity() {
        OrderShipmentLineEntity entity = new OrderShipmentLineEntity();
        entity.setId(201L);
        entity.setShipmentId(200L);
        entity.setOrderId(10L);
        entity.setSkuId(7L);
        entity.setProductName("Momo");
        entity.setQuantity(1);
        return entity;
    }

    private static OrderReview review() {
        return new OrderReview(
                300L,
                10L,
                42L,
                7L,
                5,
                "fast",
                List.of("/images/review/1.png"),
                true,
                LocalDateTime.parse("2026-07-04T09:00:00"));
    }

    private static OrderReviewEntity reviewEntity() {
        OrderReviewEntity entity = new OrderReviewEntity();
        entity.setId(300L);
        entity.setOrderId(10L);
        entity.setUserId(42L);
        entity.setSkuId(7L);
        entity.setRating(5);
        entity.setContent("fast");
        entity.setImageUrls("/images/review/1.png");
        entity.setAnonymous(true);
        entity.setCreateTime(LocalDateTime.parse("2026-07-04T09:00:00"));
        return entity;
    }
}

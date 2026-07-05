package com.example.monkey.tracking.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.tracking.domain.ProductProfile;
import com.example.monkey.tracking.domain.TrackingEvent;
import com.example.monkey.tracking.domain.TrackingEventType;
import com.example.monkey.tracking.domain.UserProfileTag;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JpaTrackingStoreTest {

    @Mock
    private TrackingEventRepository eventRepository;

    @Mock
    private UserProfileTagRepository userProfileTagRepository;

    @Mock
    private ProductProfileRepository productProfileRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PiiCryptoService piiCryptoService;

    private JpaTrackingStore store;

    @BeforeEach
    void setUp() {
        store = new JpaTrackingStore(
                eventRepository,
                userProfileTagRepository,
                productProfileRepository,
                jdbcTemplate,
                piiCryptoService,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void saveEventSerializesAttributesAndDelegatesDashboardAggregates() {
        when(eventRepository.save(any(TrackingEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbcTemplate.queryForObject(
                        eq("SELECT COUNT(1) FROM product_spu WHERE id = ?"), eq(Integer.class), eq(42L)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(1) FROM orders WHERE id = ?"), eq(Integer.class), eq(900L)))
                .thenReturn(1);
        LocalDateTime occurredAt = LocalDateTime.parse("2026-07-04T10:00:00");

        TrackingEvent saved = store.saveEvent(new TrackingEvent(
                101L,
                7L,
                "session-a",
                "trace-a",
                TrackingEventType.PAYMENT_SUCCESS,
                "/payment",
                "web",
                42L,
                5L,
                900L,
                new BigDecimal("99.90"),
                Map.of("keyword", "phone"),
                occurredAt));

        TrackingEventEntity entity = captureEvent();
        assertThat(entity.getAttributesJson()).contains("keyword", "phone");
        assertThat(entity.getAmount()).isEqualByComparingTo("99.90");
        assertThat(entity.getProductId()).isEqualTo(42L);
        assertThat(entity.getOrderId()).isEqualTo(900L);
        assertThat(saved.attributes()).containsEntry("keyword", "phone");
        assertThat(saved.eventType()).isEqualTo(TrackingEventType.PAYMENT_SUCCESS);

        when(eventRepository.countByEventTypeAndOccurredAtGreaterThanEqual(TrackingEventType.PAGE_VIEW, occurredAt))
                .thenReturn(12L);
        when(eventRepository.countDistinctVisitors(occurredAt)).thenReturn(8L);
        when(eventRepository.sumAmountByEventTypeSince(TrackingEventType.PAYMENT_SUCCESS, occurredAt))
                .thenReturn(new BigDecimal("321.00"));

        assertThat(store.countEvents(TrackingEventType.PAGE_VIEW, occurredAt)).isEqualTo(12L);
        assertThat(store.countDistinctVisitors(occurredAt)).isEqualTo(8L);
        assertThat(store.sumPaymentAmount(occurredAt)).isEqualByComparingTo("321.00");
    }

    @Test
    void saveEventDropsMissingOptionalReferencesBeforeDatabaseWrite() {
        when(eventRepository.save(any(TrackingEventEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jdbcTemplate.queryForObject(
                        eq("SELECT COUNT(1) FROM product_spu WHERE id = ?"), eq(Integer.class), eq(404L)))
                .thenReturn(0);
        when(jdbcTemplate.queryForObject(eq("SELECT COUNT(1) FROM orders WHERE id = ?"), eq(Integer.class), eq(905L)))
                .thenReturn(0);

        TrackingEvent saved = store.saveEvent(new TrackingEvent(
                102L,
                null,
                "session-b",
                "trace-b",
                TrackingEventType.PRODUCT_VIEW,
                "/shop/404",
                "web",
                404L,
                5L,
                905L,
                null,
                Map.of(),
                LocalDateTime.parse("2026-07-04T10:05:00")));

        TrackingEventEntity entity = captureEvent();
        assertThat(entity.getProductId()).isNull();
        assertThat(entity.getOrderId()).isNull();
        assertThat(saved.productId()).isNull();
        assertThat(saved.orderId()).isNull();
    }

    @Test
    void saveUserProfileEncryptsSummaryAndRoundTripsTags() {
        when(userProfileTagRepository.findById(7L)).thenReturn(Optional.empty());
        when(piiCryptoService.encrypt("last=PAYMENT_SUCCESS")).thenReturn("enc-summary");
        when(piiCryptoService.blindIndex("last=PAYMENT_SUCCESS")).thenReturn("summary-hmac");
        when(piiCryptoService.decrypt("enc-summary")).thenReturn("last=PAYMENT_SUCCESS");
        when(userProfileTagRepository.save(any(UserProfileTagEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileTag saved = store.saveUserProfile(new UserProfileTag(
                7L,
                "last=PAYMENT_SUCCESS",
                List.of("event:payment_success"),
                List.of("product:42", "category:5"),
                LocalDateTime.parse("2026-07-04T11:00:00"),
                0L));

        UserProfileTagEntity entity = captureUserProfile();
        assertThat(entity.getEncryptedProfileSummary()).isEqualTo("enc-summary");
        assertThat(entity.getProfileSummaryHmac()).isEqualTo("summary-hmac");
        assertThat(entity.getBehaviorTagsJson()).contains("event:payment_success");
        assertThat(saved.profileSummary()).isEqualTo("last=PAYMENT_SUCCESS");
        assertThat(saved.interestTags()).containsExactly("product:42", "category:5");
    }

    @Test
    void saveProductProfileSerializesTagVector() {
        when(productProfileRepository.findById(42L)).thenReturn(Optional.empty());
        when(productProfileRepository.save(any(ProductProfileEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductProfile saved = store.saveProductProfile(new ProductProfile(
                42L,
                5L,
                List.of("product:42", "category:5"),
                3L,
                new BigDecimal("4.80"),
                LocalDateTime.parse("2026-07-04T12:00:00"),
                0L));

        ProductProfileEntity entity = captureProductProfile();
        assertThat(entity.getTagVectorJson()).contains("product:42", "category:5");
        assertThat(entity.getSalesCount()).isEqualTo(3L);
        assertThat(saved.reviewScore()).isEqualByComparingTo("4.80");
    }

    private TrackingEventEntity captureEvent() {
        ArgumentCaptor<TrackingEventEntity> captor = ArgumentCaptor.forClass(TrackingEventEntity.class);
        verify(eventRepository).save(captor.capture());
        return captor.getValue();
    }

    private UserProfileTagEntity captureUserProfile() {
        ArgumentCaptor<UserProfileTagEntity> captor = ArgumentCaptor.forClass(UserProfileTagEntity.class);
        verify(userProfileTagRepository).save(captor.capture());
        return captor.getValue();
    }

    private ProductProfileEntity captureProductProfile() {
        ArgumentCaptor<ProductProfileEntity> captor = ArgumentCaptor.forClass(ProductProfileEntity.class);
        verify(productProfileRepository).save(captor.capture());
        return captor.getValue();
    }
}

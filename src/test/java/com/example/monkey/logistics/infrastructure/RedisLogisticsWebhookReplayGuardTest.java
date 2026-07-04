package com.example.monkey.logistics.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.shared.domain.id.IdGenerator;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class RedisLogisticsWebhookReplayGuardTest {

    @Mock
    private ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    @Mock
    private LogisticsWebhookLogRepository webhookLogRepository;

    @Mock
    private IdGenerator idGenerator;

    private RedisLogisticsWebhookReplayGuard guard;

    @BeforeEach
    void setUp() {
        when(redisTemplateProvider.getIfAvailable()).thenReturn(null);
        guard = new RedisLogisticsWebhookReplayGuard(redisTemplateProvider, webhookLogRepository, idGenerator);
    }

    @Test
    void reservesWebhookThroughDatabaseFallbackWhenRedisIsUnavailable() {
        when(webhookLogRepository.findByCarrierAndEventId(LogisticsCarrier.SF, "event-1"))
                .thenReturn(Optional.empty());
        when(idGenerator.nextId()).thenReturn(9000L);
        when(webhookLogRepository.save(any(LogisticsWebhookLogEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        boolean first = guard.reserve(LogisticsCarrier.SF, "SF7000", "event-1", Duration.ofHours(24), "127.0.0.1");

        assertThat(first).isTrue();
        LogisticsWebhookLogEntity entity = captureLog();
        assertThat(entity.getId()).isEqualTo(9000L);
        assertThat(entity.getCarrier()).isEqualTo(LogisticsCarrier.SF);
        assertThat(entity.getTrackingNo()).isEqualTo("SF7000");
        assertThat(entity.getSourceIp()).isEqualTo("127.0.0.1");
    }

    @Test
    void rejectsReplayAlreadyPresentInDatabase() {
        when(webhookLogRepository.findByCarrierAndEventId(LogisticsCarrier.SF, "event-1"))
                .thenReturn(Optional.of(new LogisticsWebhookLogEntity()));

        assertThat(guard.reserve(LogisticsCarrier.SF, "SF7000", "event-1", Duration.ofHours(24), "127.0.0.1"))
                .isFalse();
    }

    private LogisticsWebhookLogEntity captureLog() {
        ArgumentCaptor<LogisticsWebhookLogEntity> captor = ArgumentCaptor.forClass(LogisticsWebhookLogEntity.class);
        verify(webhookLogRepository).save(captor.capture());
        return captor.getValue();
    }
}

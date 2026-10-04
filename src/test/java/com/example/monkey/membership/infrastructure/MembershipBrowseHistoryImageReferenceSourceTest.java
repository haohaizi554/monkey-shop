package com.example.monkey.membership.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MembershipBrowseHistoryImageReferenceSourceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 28, 10, 0);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void readsOnlyNonexpiredImagesForCapturedTenantAndPreservesDuplicateReferences() {
        JdbcTemplate jdbcTemplate = mock();
        AtomicInteger queryCalls = new AtomicInteger();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> queryCalls.getAndIncrement() == 0
                        ? List.of(
                                row(invocation, 10L, "/images/shared.png", NOW.plusMinutes(1)),
                                row(invocation, 11L, "/images/shared.png", NOW.plusMinutes(2)),
                                row(invocation, 12L, "/images/expired.png", NOW.minusMinutes(1)))
                        : List.of());
        MembershipBrowseHistoryImageReferenceSource source = new MembershipBrowseHistoryImageReferenceSource(
                jdbcTemplate, 3, Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneOffset.UTC));
        TenantContext.setTenantId(22L);
        List<String> references = new ArrayList<>();

        source.forEachReferencedImagePath(references::add);

        assertThat(references).containsExactly("/images/shared.png", "/images/shared.png");
        var query = org.mockito.ArgumentCaptor.forClass(String.class);
        var arguments = org.mockito.ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbcTemplate, times(2))
                .query(query.capture(), any(RowMapper.class), arguments.capture());
        assertThat(query.getValue())
                .containsIgnoringCase("FROM membership_browse_history")
                .containsIgnoringCase("tenant_id = ?")
                .containsIgnoringCase("expires_at > ?")
                .containsIgnoringCase("product_image IS NOT NULL")
                .containsIgnoringCase("ORDER BY id")
                .containsIgnoringCase("LIMIT ?");
        assertThat(arguments.getAllValues().getFirst()).containsExactly(22L, NOW, 0L, 3);
    }

    @Test
    void readFailureIsPropagatedSoCleanupCannotPublishAnIncompleteSnapshot() {
        JdbcTemplate jdbcTemplate = mock();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        MembershipBrowseHistoryImageReferenceSource source =
                new MembershipBrowseHistoryImageReferenceSource(jdbcTemplate, 10, Clock.systemUTC());
        TenantContext.setTenantId(22L);

        assertThatThrownBy(() -> source.forEachReferencedImagePath(ignored -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");
    }

    private static Object row(InvocationOnMock invocation, long id, String image, LocalDateTime expiresAt)
            throws Exception {
        @SuppressWarnings("rawtypes")
        RowMapper mapper = invocation.getArgument(1);
        ResultSet resultSet = mock();
        when(resultSet.getLong("id")).thenReturn(id);
        when(resultSet.getString("product_image")).thenReturn(image);
        when(resultSet.getTimestamp("expires_at")).thenReturn(Timestamp.valueOf(expiresAt));
        return mapper.mapRow(resultSet, 0);
    }
}

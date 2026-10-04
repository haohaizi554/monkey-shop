package com.example.monkey.membership.infrastructure;

import com.example.monkey.shared.application.tenant.TenantContext;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.domain.storage.StoredImageReferenceSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(430)
public class MembershipBrowseHistoryImageReferenceSource implements StoredImageReferenceSource {

    private static final String LIVE_IMAGE_QUERY = """
            SELECT id, product_image, expires_at
            FROM membership_browse_history
            WHERE tenant_id = ?
              AND expires_at > ?
              AND product_image IS NOT NULL
              AND id > ?
            ORDER BY id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final int referenceScanBatchSize;
    private final Clock clock;

    @Autowired
    public MembershipBrowseHistoryImageReferenceSource(
            JdbcTemplate jdbcTemplate,
            @Value("${app.upload.cleanup.reference-scan-batch-size:500}") int referenceScanBatchSize) {
        this(jdbcTemplate, referenceScanBatchSize, Clock.systemDefaultZone());
    }

    MembershipBrowseHistoryImageReferenceSource(JdbcTemplate jdbcTemplate, int referenceScanBatchSize, Clock clock) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.referenceScanBatchSize = Math.max(1, referenceScanBatchSize);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean isUsed(String imagePath) {
        if (!ImageReferenceService.isTrackable(imagePath)) {
            return false;
        }
        String canonicalPath = canonicalPath(imagePath);
        AtomicBoolean used = new AtomicBoolean();
        forEachReferencedImagePath(productImage -> {
            if (canonicalPath(productImage).equals(canonicalPath)) {
                used.set(true);
            }
        });
        return used.get();
    }

    @Override
    @Transactional(readOnly = true)
    public void forEachReferencedImagePath(Consumer<String> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        long tenantId = TenantContext.currentTenantIdOrDefault();
        LocalDateTime now = LocalDateTime.now(clock);
        long lastId = 0L;
        while (true) {
            List<BrowseHistoryImage> rows = readBatch(tenantId, now, lastId);
            if (rows.isEmpty()) {
                return;
            }
            long nextLastId = lastId;
            for (BrowseHistoryImage row : rows) {
                if (row.id() <= lastId) {
                    throw new IllegalStateException("Membership browse history scan did not advance by id");
                }
                nextLastId = Math.max(nextLastId, row.id());
                if (row.expiresAt().isAfter(now) && ImageReferenceService.isTrackable(row.productImage())) {
                    consumer.accept(row.productImage().trim());
                }
            }
            if (nextLastId <= lastId) {
                throw new IllegalStateException("Membership browse history scan did not advance");
            }
            lastId = nextLastId;
            if (rows.size() < referenceScanBatchSize) {
                return;
            }
        }
    }

    private List<BrowseHistoryImage> readBatch(long tenantId, LocalDateTime now, long lastId) {
        List<BrowseHistoryImage> rows = jdbcTemplate.query(
                LIVE_IMAGE_QUERY,
                MembershipBrowseHistoryImageReferenceSource::toImage,
                tenantId,
                now,
                lastId,
                referenceScanBatchSize);
        if (rows == null) {
            throw new IllegalStateException("Database returned no membership browse history rows");
        }
        return rows;
    }

    private static BrowseHistoryImage toImage(ResultSet resultSet, int rowNum) throws SQLException {
        Timestamp expiresAt = resultSet.getTimestamp("expires_at");
        if (expiresAt == null) {
            throw new IllegalStateException("Membership browse history row has no expiry");
        }
        return new BrowseHistoryImage(
                resultSet.getLong("id"), resultSet.getString("product_image"), expiresAt.toLocalDateTime());
    }

    private static String canonicalPath(String imagePath) {
        return ImageReferenceService.canonicalPath(imagePath.trim());
    }

    private record BrowseHistoryImage(long id, String productImage, LocalDateTime expiresAt) {}
}

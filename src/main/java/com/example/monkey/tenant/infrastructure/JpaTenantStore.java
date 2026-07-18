package com.example.monkey.tenant.infrastructure;

import com.example.monkey.shared.application.tenant.TenantAccessGateway;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.tenant.domain.Tenant;
import com.example.monkey.tenant.domain.TenantBill;
import com.example.monkey.tenant.domain.TenantConfig;
import com.example.monkey.tenant.domain.TenantDataExportJob;
import com.example.monkey.tenant.domain.TenantExportStatus;
import com.example.monkey.tenant.domain.TenantStatus;
import com.example.monkey.tenant.domain.TenantStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(name = "app.tenant.store", havingValue = "jpa", matchIfMissing = true)
public class JpaTenantStore implements TenantStore, TenantAccessGateway {

    private static final TypeReference<Map<String, String>> SETTINGS_TYPE = new TypeReference<>() {};

    private final TenantRepository tenantRepository;
    private final TenantConfigRepository configRepository;
    private final TenantConfigHistoryRepository historyRepository;
    private final TenantBillRepository billRepository;
    private final TenantDataExportJobRepository exportJobRepository;
    private final PiiCryptoService piiCryptoService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;

    public JpaTenantStore(
            TenantRepository tenantRepository,
            TenantConfigRepository configRepository,
            TenantConfigHistoryRepository historyRepository,
            TenantBillRepository billRepository,
            TenantDataExportJobRepository exportJobRepository,
            PiiCryptoService piiCryptoService,
            ObjectMapper objectMapper,
            IdGenerator idGenerator) {
        this.tenantRepository = tenantRepository;
        this.configRepository = configRepository;
        this.historyRepository = historyRepository;
        this.billRepository = billRepository;
        this.exportJobRepository = exportJobRepository;
        this.piiCryptoService = piiCryptoService;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    @Override
    public Tenant saveTenant(Tenant tenant) {
        return toDomain(tenantRepository.save(toEntity(tenant)));
    }

    @Override
    public Optional<Tenant> findTenant(Long tenantId) {
        return tenantRepository.findById(tenantId).map(this::toDomain);
    }

    @Override
    public boolean isServiceableTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            return false;
        }
        return tenantRepository
                .findById(tenantId)
                .filter(entity -> entity.getStatus() == TenantStatus.TRIAL
                        || entity.getStatus() == TenantStatus.ACTIVE
                        || entity.getStatus() == TenantStatus.DOWNGRADED)
                .filter(entity ->
                        entity.getExpiresAt() != null && entity.getExpiresAt().isAfter(LocalDateTime.now()))
                .isPresent();
    }

    @Override
    public Optional<Tenant> findTenantByCode(String code) {
        return StringUtils.hasText(code)
                ? tenantRepository.findByCode(code.trim().toLowerCase()).map(this::toDomain)
                : Optional.empty();
    }

    @Override
    public List<Tenant> findTenants() {
        return tenantRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countTenantsByStatus(TenantStatus status) {
        return tenantRepository.countByStatus(status);
    }

    @Override
    public TenantConfig saveConfig(TenantConfig config, Long operatorUserId) {
        Optional<TenantConfigEntity> existing = configRepository.findByTenantIdAndConfigTypeAndProvider(
                config.tenantId(), config.configType(), config.provider());
        String oldSettings = existing.map(TenantConfigEntity::getSettingsJson).orElse(null);
        TenantConfigEntity entity = existing.orElseGet(TenantConfigEntity::new);
        entity.setId(existing.map(TenantConfigEntity::getId).orElse(config.id()));
        entity.setTenantId(config.tenantId());
        entity.setConfigType(config.configType());
        entity.setProvider(config.provider());
        entity.setSettingsJson(write(config.settings()));
        entity.setEnabled(config.enabled());
        entity.setUpdatedAt(config.updatedAt());
        entity.setVersion(existing.map(TenantConfigEntity::getVersion).orElse(config.version()));
        TenantConfigEntity saved = configRepository.save(entity);
        saveConfigHistory(saved, oldSettings, operatorUserId);
        return toDomain(saved);
    }

    @Override
    public List<TenantConfig> findConfigs(Long tenantId) {
        return configRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public TenantBill saveBill(TenantBill bill) {
        TenantBillEntity entity = billRepository
                .findByTenantIdAndBillingMonth(
                        bill.tenantId(), bill.billingMonth().toString())
                .orElseGet(TenantBillEntity::new);
        entity.setId(entity.getId() == null ? bill.id() : entity.getId());
        entity.setTenantId(bill.tenantId());
        entity.setBillingMonth(bill.billingMonth().toString());
        entity.setPlan(bill.plan());
        entity.setOrderCount(bill.orderCount());
        entity.setMonthlyFee(bill.monthlyFee());
        entity.setUsageFee(bill.usageFee());
        entity.setTotalAmount(bill.totalAmount());
        entity.setPaymentAmount(bill.paymentAmount());
        entity.setStatus(bill.status());
        entity.setGeneratedAt(bill.generatedAt());
        entity.setReconciledAt(bill.reconciledAt());
        entity.setVersion(entity.getVersion() == null ? bill.version() : entity.getVersion());
        return toDomain(billRepository.save(entity));
    }

    @Override
    public List<TenantBill> findBills(Long tenantId) {
        return billRepository.findByTenantIdOrderByGeneratedAtDesc(tenantId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countOrdersForTenant(Long tenantId, YearMonth month) {
        return billRepository.countOrdersForTenant(
                tenantId,
                month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay());
    }

    @Override
    public BigDecimal sumPaidAmountForTenant(Long tenantId, YearMonth month) {
        BigDecimal amount = billRepository.sumPaidAmountForTenant(
                tenantId,
                month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay());
        return amount == null ? BigDecimal.ZERO : amount;
    }

    @Override
    @Transactional
    public TenantDataExportJob saveExportJob(TenantDataExportJob exportJob) {
        Optional<TenantDataExportJobEntity> existing = exportJobRepository.findById(exportJob.id());
        TenantDataExportJobEntity entity = existing.orElseGet(TenantDataExportJobEntity::new);
        requireCurrentExportVersion(exportJob, existing);
        entity.setId(exportJob.id());
        entity.setTenantId(exportJob.tenantId());
        entity.setExportType(exportJob.exportType());
        entity.setStatus(exportJob.status());
        entity.setProviderJobId(exportJob.providerJobId());
        entity.setArtifactUri(exportJob.artifactUri());
        entity.setRequestedBy(exportJob.requestedBy());
        entity.setRequestedAt(exportJob.requestedAt());
        entity.setCompletedAt(exportJob.completedAt());
        entity.setAuditTraceId(exportJob.auditTraceId());
        entity.setErrorMessage(exportJob.errorMessage());
        return toDomain(exportJobRepository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantDataExportJob> findExportJob(Long tenantId, Long jobId) {
        return exportJobRepository.findByIdAndTenantId(jobId, tenantId).map(this::toDomain);
    }

    @Override
    public List<TenantDataExportJob> findExportJobs(Long tenantId) {
        return exportJobRepository.findByTenantIdOrderByRequestedAtDesc(tenantId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<TenantDataExportJob> findPendingExportJobs(int limit) {
        return exportJobRepository
                .findByStatusInOrderByRequestedAtAsc(
                        List.of(TenantExportStatus.QUEUED, TenantExportStatus.RUNNING),
                        PageRequest.of(0, Math.max(1, limit)))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private TenantEntity toEntity(Tenant tenant) {
        TenantEntity entity = tenantRepository.findById(tenant.id()).orElseGet(TenantEntity::new);
        entity.setId(tenant.id());
        entity.setCode(tenant.code());
        entity.setName(tenant.name());
        entity.setStatus(tenant.status());
        entity.setPlan(tenant.plan());
        entity.setContactName(tenant.contactName());
        entity.setEncryptedContactPhone(piiCryptoService.encrypt(tenant.contactPhone()));
        entity.setContactPhoneHmac(piiCryptoService.blindIndex(tenant.contactPhone()));
        entity.setCreatedAt(tenant.createdAt());
        entity.setExpiresAt(tenant.expiresAt());
        entity.setVersion(tenant.version());
        return entity;
    }

    private Tenant toDomain(TenantEntity entity) {
        return new Tenant(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getStatus(),
                entity.getPlan(),
                entity.getContactName(),
                decrypt(entity.getEncryptedContactPhone()),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getVersion() == null ? 0L : entity.getVersion());
    }

    private TenantConfig toDomain(TenantConfigEntity entity) {
        return new TenantConfig(
                entity.getId(),
                entity.getTenantId(),
                entity.getConfigType(),
                entity.getProvider(),
                read(entity.getSettingsJson()),
                entity.isEnabled(),
                entity.getUpdatedAt(),
                entity.getVersion() == null ? 0L : entity.getVersion());
    }

    private TenantBill toDomain(TenantBillEntity entity) {
        return new TenantBill(
                entity.getId(),
                entity.getTenantId(),
                YearMonth.parse(entity.getBillingMonth()),
                entity.getPlan(),
                entity.getOrderCount(),
                entity.getMonthlyFee(),
                entity.getUsageFee(),
                entity.getTotalAmount(),
                entity.getPaymentAmount(),
                entity.getStatus(),
                entity.getGeneratedAt(),
                entity.getReconciledAt(),
                entity.getVersion() == null ? 0L : entity.getVersion());
    }

    private TenantDataExportJob toDomain(TenantDataExportJobEntity entity) {
        return new TenantDataExportJob(
                entity.getId(),
                entity.getTenantId(),
                entity.getExportType(),
                entity.getStatus(),
                entity.getProviderJobId(),
                entity.getArtifactUri(),
                entity.getRequestedBy(),
                entity.getRequestedAt(),
                entity.getCompletedAt(),
                entity.getAuditTraceId(),
                entity.getErrorMessage(),
                entity.getVersion() == null ? 0L : entity.getVersion());
    }

    private static void requireCurrentExportVersion(
            TenantDataExportJob exportJob, Optional<TenantDataExportJobEntity> existing) {
        if (existing.isEmpty()) {
            if (exportJob.version() != 0L) {
                throw staleExport(exportJob.id());
            }
            return;
        }
        long persistedVersion = existing.orElseThrow().getVersion();
        if (exportJob.version() <= 0L || persistedVersion != exportJob.version() - 1L) {
            throw staleExport(exportJob.id());
        }
    }

    private static OptimisticLockingFailureException staleExport(Long jobId) {
        return new OptimisticLockingFailureException("Tenant export job " + jobId + " has a stale version");
    }

    private void saveConfigHistory(TenantConfigEntity saved, String oldSettings, Long operatorUserId) {
        TenantConfigHistoryEntity history = new TenantConfigHistoryEntity();
        history.setId(idGenerator.nextId());
        history.setTenantId(saved.getTenantId());
        history.setConfigId(saved.getId());
        history.setOldSettingsJson(oldSettings);
        history.setNewSettingsJson(saved.getSettingsJson());
        history.setOperatorUserId(operatorUserId);
        history.setChangedAt(saved.getUpdatedAt());
        historyRepository.save(history);
    }

    private String decrypt(String value) {
        return StringUtils.hasText(value) ? piiCryptoService.decrypt(value) : "";
    }

    private String write(Map<String, String> settings) {
        try {
            return objectMapper.writeValueAsString(settings == null ? Map.of() : settings);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tenant settings cannot be serialized", exception);
        }
    }

    private Map<String, String> read(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, SETTINGS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tenant settings cannot be deserialized", exception);
        }
    }
}

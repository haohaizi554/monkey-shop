package com.example.monkey.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.admin.application.StatsService;
import com.example.monkey.admin.application.dto.StatsResponseDto;
import com.example.monkey.admin.domain.AdminStatsReader;
import com.example.monkey.admin.infrastructure.JpaAdminStatsReader;
import com.example.monkey.admin.interfaces.StatsController;
import com.example.monkey.admin.interfaces.dto.AuditTraceRequestDto;
import com.example.monkey.admin.interfaces.dto.StatsQueryRequestDto;
import com.example.monkey.cart.application.CartApplicationService;
import com.example.monkey.cart.application.dto.CartAddItemRequestDto;
import com.example.monkey.cart.application.dto.CartCheckoutRequestDto;
import com.example.monkey.cart.application.dto.CartResponseDto;
import com.example.monkey.cart.domain.CartCatalogReader;
import com.example.monkey.cart.domain.CartCheckoutStore;
import com.example.monkey.cart.domain.CartItem;
import com.example.monkey.cart.domain.CartLockManager;
import com.example.monkey.cart.domain.CartStore;
import com.example.monkey.cart.infrastructure.CartCheckoutEntity;
import com.example.monkey.cart.infrastructure.JpaCartCatalogReader;
import com.example.monkey.cart.infrastructure.JpaCartCheckoutStore;
import com.example.monkey.cart.infrastructure.RedisCartStore;
import com.example.monkey.cart.infrastructure.RedissonCartLockManager;
import com.example.monkey.cart.interfaces.CartController;
import com.example.monkey.inventory.application.InventoryApplicationService;
import com.example.monkey.inventory.application.dto.InventoryCompensateRequestDto;
import com.example.monkey.inventory.application.dto.InventoryReconciliationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReservationResponseDto;
import com.example.monkey.inventory.application.dto.InventoryReserveRequestDto;
import com.example.monkey.inventory.application.dto.WarehouseStockResponseDto;
import com.example.monkey.inventory.domain.InventoryLockManager;
import com.example.monkey.inventory.domain.InventoryStore;
import com.example.monkey.inventory.domain.WarehouseStock;
import com.example.monkey.inventory.infrastructure.InventoryReservationEntity;
import com.example.monkey.inventory.infrastructure.InventoryReservationRepository;
import com.example.monkey.inventory.infrastructure.InventoryStock;
import com.example.monkey.inventory.infrastructure.InventoryStockLedger;
import com.example.monkey.inventory.infrastructure.InventoryStockLedgerRepository;
import com.example.monkey.inventory.infrastructure.InventoryStockRepository;
import com.example.monkey.inventory.infrastructure.InventoryWarehouse;
import com.example.monkey.inventory.infrastructure.InventoryWarehouseRepository;
import com.example.monkey.inventory.infrastructure.JpaInventoryStore;
import com.example.monkey.inventory.infrastructure.RedissonInventoryLockManager;
import com.example.monkey.inventory.interfaces.InventoryController;
import com.example.monkey.logistics.application.LogisticsApplicationService;
import com.example.monkey.logistics.application.dto.FreightQuoteRequestDto;
import com.example.monkey.logistics.application.dto.LogisticsTrackingResponseDto;
import com.example.monkey.logistics.application.dto.ShipmentCreateRequestDto;
import com.example.monkey.logistics.application.dto.TrackingWebhookRequestDto;
import com.example.monkey.logistics.domain.AddressParser;
import com.example.monkey.logistics.domain.FreightTemplate;
import com.example.monkey.logistics.domain.LogisticsGateway;
import com.example.monkey.logistics.domain.LogisticsStore;
import com.example.monkey.logistics.domain.LogisticsTracking;
import com.example.monkey.logistics.domain.LogisticsTransitionResolver;
import com.example.monkey.logistics.domain.LogisticsWebhookReplayGuard;
import com.example.monkey.logistics.domain.TrackingEventRecord;
import com.example.monkey.logistics.infrastructure.FreightTemplateEntity;
import com.example.monkey.logistics.infrastructure.JpaLogisticsStore;
import com.example.monkey.logistics.infrastructure.LogisticsTrackingEntity;
import com.example.monkey.logistics.infrastructure.LogisticsTrackingEventEntity;
import com.example.monkey.logistics.infrastructure.LogisticsWebhookLogEntity;
import com.example.monkey.logistics.infrastructure.RedisLogisticsWebhookReplayGuard;
import com.example.monkey.logistics.infrastructure.RuleBasedAddressParser;
import com.example.monkey.logistics.infrastructure.SandboxLogisticsGateway;
import com.example.monkey.logistics.infrastructure.SpringStateMachineLogisticsTransitionResolver;
import com.example.monkey.logistics.interfaces.LogisticsController;
import com.example.monkey.marketing.application.MarketingApplicationService;
import com.example.monkey.marketing.application.dto.CouponClaimRequestDto;
import com.example.monkey.marketing.application.dto.GroupBuyJoinRequestDto;
import com.example.monkey.marketing.application.dto.SeckillRequestDto;
import com.example.monkey.marketing.domain.CouponDefinition;
import com.example.monkey.marketing.domain.MarketingIdempotencyStore;
import com.example.monkey.marketing.domain.MarketingLockManager;
import com.example.monkey.marketing.domain.MarketingStore;
import com.example.monkey.marketing.domain.SeckillActivity;
import com.example.monkey.marketing.infrastructure.JpaMarketingStore;
import com.example.monkey.marketing.infrastructure.MarketingCouponEntity;
import com.example.monkey.marketing.infrastructure.MarketingCouponRepository;
import com.example.monkey.marketing.infrastructure.MarketingSeckillActivityEntity;
import com.example.monkey.marketing.infrastructure.RedisMarketingIdempotencyStore;
import com.example.monkey.marketing.infrastructure.RedissonMarketingLockManager;
import com.example.monkey.marketing.interfaces.MarketingController;
import com.example.monkey.order.application.OrderApplicationService;
import com.example.monkey.order.application.OrderIdempotencyService;
import com.example.monkey.order.application.OrderOwnershipService;
import com.example.monkey.order.application.OrderService;
import com.example.monkey.order.application.dto.OrderPageQuery;
import com.example.monkey.order.application.dto.OrderResponseDto;
import com.example.monkey.order.application.observability.BusinessMetricsService;
import com.example.monkey.order.domain.OrderFulfillmentStore;
import com.example.monkey.order.domain.OrderIdempotencyKeyStore;
import com.example.monkey.order.domain.OrderIdempotencyStore;
import com.example.monkey.order.domain.OrderLockManager;
import com.example.monkey.order.domain.OrderNumberGenerator;
import com.example.monkey.order.domain.OrderOwnershipChecker;
import com.example.monkey.order.domain.OrderStore;
import com.example.monkey.order.domain.OrderTransitionResolver;
import com.example.monkey.order.domain.PendingOrderCounter;
import com.example.monkey.order.infrastructure.IdempotencyRecord;
import com.example.monkey.order.infrastructure.IdempotencyRecordRepository;
import com.example.monkey.order.infrastructure.JpaOrderFulfillmentStore;
import com.example.monkey.order.infrastructure.JpaOrderStore;
import com.example.monkey.order.infrastructure.Order;
import com.example.monkey.order.infrastructure.OrderFulfillmentItemEntity;
import com.example.monkey.order.infrastructure.OrderImageReferenceSource;
import com.example.monkey.order.infrastructure.OrderRepository;
import com.example.monkey.order.infrastructure.OrderReviewEntity;
import com.example.monkey.order.infrastructure.OrderShipmentBatchEntity;
import com.example.monkey.order.infrastructure.StockLog;
import com.example.monkey.order.infrastructure.StockLogRepository;
import com.example.monkey.order.interfaces.OrderController;
import com.example.monkey.order.interfaces.OrderOwnership;
import com.example.monkey.order.interfaces.dto.CreateOrderRequestDto;
import com.example.monkey.payment.application.PaymentApplicationService;
import com.example.monkey.payment.application.dto.PaymentCallbackRequestDto;
import com.example.monkey.payment.application.dto.PaymentCreateRequestDto;
import com.example.monkey.payment.application.dto.PaymentReconciliationRequestDto;
import com.example.monkey.payment.application.dto.PaymentRefundRequestDto;
import com.example.monkey.payment.application.dto.PaymentResponseDto;
import com.example.monkey.payment.domain.PaymentCallbackReplayGuard;
import com.example.monkey.payment.domain.PaymentGateway;
import com.example.monkey.payment.domain.PaymentLedgerEntry;
import com.example.monkey.payment.domain.PaymentOrder;
import com.example.monkey.payment.domain.PaymentReconciliationReport;
import com.example.monkey.payment.domain.PaymentStore;
import com.example.monkey.payment.domain.PaymentTransitionResolver;
import com.example.monkey.payment.infrastructure.JpaPaymentStore;
import com.example.monkey.payment.infrastructure.PaymentCallbackLogEntity;
import com.example.monkey.payment.infrastructure.PaymentLedgerEntity;
import com.example.monkey.payment.infrastructure.PaymentOrderEntity;
import com.example.monkey.payment.infrastructure.PaymentReconciliationReportEntity;
import com.example.monkey.payment.infrastructure.RedisPaymentCallbackReplayGuard;
import com.example.monkey.payment.infrastructure.SandboxPaymentGateway;
import com.example.monkey.payment.infrastructure.SpringStateMachinePaymentTransitionResolver;
import com.example.monkey.payment.interfaces.PaymentController;
import com.example.monkey.product.application.MonkeyService;
import com.example.monkey.product.application.dto.MonkeyRequestDto;
import com.example.monkey.product.application.dto.MonkeyResponseDto;
import com.example.monkey.product.application.dto.ProductPageQuery;
import com.example.monkey.product.domain.ProductCatalog;
import com.example.monkey.product.infrastructure.JpaProductCatalog;
import com.example.monkey.product.infrastructure.Monkey;
import com.example.monkey.product.infrastructure.MonkeyRepository;
import com.example.monkey.product.infrastructure.ProductImageReferenceSource;
import com.example.monkey.product.interfaces.MonkeyController;
import com.example.monkey.shared.application.dto.PageResponseDto;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.observability.VisitMetricsService;
import com.example.monkey.shared.application.observability.dto.AuditTraceEventDto;
import com.example.monkey.shared.application.security.ApiRateLimitApplicationService;
import com.example.monkey.shared.application.security.ApiRateLimitOperation;
import com.example.monkey.shared.application.security.ApiRateLimitResult;
import com.example.monkey.shared.application.security.AuthenticatedPrincipals;
import com.example.monkey.shared.application.security.CaptchaChallengeResult;
import com.example.monkey.shared.application.security.SessionTokenPair;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.application.storage.FileService;
import com.example.monkey.shared.application.storage.ImageCleanupService;
import com.example.monkey.shared.application.storage.ImageVariantService;
import com.example.monkey.shared.application.storage.UploadDtoAssembler;
import com.example.monkey.shared.application.storage.UploadFileContent;
import com.example.monkey.shared.application.storage.dto.PresignedGetUrlResponseDto;
import com.example.monkey.shared.application.storage.dto.PresignedUploadResponseDto;
import com.example.monkey.shared.application.storage.dto.UploadResponseDto;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.domain.observability.AuditLogStore;
import com.example.monkey.shared.domain.observability.VisitLogRecorder;
import com.example.monkey.shared.domain.privacy.PhoneBlindIndexTarget;
import com.example.monkey.shared.domain.security.ApiRateLimiter;
import com.example.monkey.shared.domain.security.CaptchaChallenge;
import com.example.monkey.shared.domain.security.JwtTokenPair;
import com.example.monkey.shared.domain.security.RateLimitPolicy;
import com.example.monkey.shared.domain.storage.ImageReferenceService;
import com.example.monkey.shared.domain.storage.ImageUsageChecker;
import com.example.monkey.shared.domain.storage.MalwareDetectedException;
import com.example.monkey.shared.domain.storage.ObjectStorageKey;
import com.example.monkey.shared.domain.storage.ObjectStorageService;
import com.example.monkey.shared.domain.storage.StoredImageReferenceReader;
import com.example.monkey.shared.domain.storage.StoredImageReferenceSource;
import com.example.monkey.shared.domain.storage.VirusScanner;
import com.example.monkey.shared.infrastructure.config.SecurityConfig;
import com.example.monkey.shared.infrastructure.observability.AuditLog;
import com.example.monkey.shared.infrastructure.observability.AuditLogRepository;
import com.example.monkey.shared.infrastructure.observability.JpaAuditLogStore;
import com.example.monkey.shared.infrastructure.observability.JpaVisitLogRecorder;
import com.example.monkey.shared.infrastructure.observability.VisitLog;
import com.example.monkey.shared.infrastructure.observability.VisitLogRepository;
import com.example.monkey.shared.infrastructure.privacy.EncryptedStringAttributeConverter;
import com.example.monkey.shared.infrastructure.privacy.PiiBlindIndexEntityListener;
import com.example.monkey.shared.infrastructure.privacy.PiiCryptoService;
import com.example.monkey.shared.infrastructure.privacy.PiiKeyMaterialProvider;
import com.example.monkey.shared.infrastructure.security.ApiRateLimitService;
import com.example.monkey.shared.infrastructure.storage.ClamAvVirusScanner;
import com.example.monkey.shared.infrastructure.storage.CompositeImageUsageChecker;
import com.example.monkey.shared.infrastructure.storage.CompositeStoredImageReferenceReader;
import com.example.monkey.shared.infrastructure.storage.ImageTask;
import com.example.monkey.shared.infrastructure.storage.InMemoryImageReferenceService;
import com.example.monkey.shared.infrastructure.storage.LocalObjectStorageService;
import com.example.monkey.shared.infrastructure.storage.MinioObjectStorageService;
import com.example.monkey.shared.infrastructure.storage.NoOpVirusScanner;
import com.example.monkey.shared.infrastructure.storage.RedisImageReferenceService;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.security.ApiRateLimitFilter;
import com.example.monkey.shared.interfaces.storage.UploadController;
import com.example.monkey.shared.interfaces.storage.dto.PresignedGetUrlRequestDto;
import com.example.monkey.shared.interfaces.storage.dto.PresignedUploadRequestDto;
import com.example.monkey.shared.interfaces.storage.dto.UploadFileRequestDto;
import com.example.monkey.shared.interfaces.storage.dto.UploadRequestDto;
import com.example.monkey.shared.interfaces.web.ErrorHttpStatuses;
import com.example.monkey.user.application.AddressApplicationService;
import com.example.monkey.user.application.AddressService;
import com.example.monkey.user.application.AuthDtoAssembler;
import com.example.monkey.user.application.AuthResponseService;
import com.example.monkey.user.application.AuthenticatedUserPrincipal;
import com.example.monkey.user.application.AuthenticationApplicationService;
import com.example.monkey.user.application.CaptchaService;
import com.example.monkey.user.application.LoginApplicationService;
import com.example.monkey.user.application.LoginAttemptApplicationService;
import com.example.monkey.user.application.PasswordChangeApplicationService;
import com.example.monkey.user.application.PasswordResetApplicationService;
import com.example.monkey.user.application.PasswordResetChallengeApplicationService;
import com.example.monkey.user.application.PiiRetentionService;
import com.example.monkey.user.application.PrivacyApplicationService;
import com.example.monkey.user.application.RefreshTokenApplicationService;
import com.example.monkey.user.application.RegistrationApplicationService;
import com.example.monkey.user.application.SessionTokenApplicationService;
import com.example.monkey.user.application.UserDtoAssembler;
import com.example.monkey.user.application.UserProfileApplicationService;
import com.example.monkey.user.application.UserService;
import com.example.monkey.user.application.dto.AddressPageQuery;
import com.example.monkey.user.application.dto.AddressRequestDto;
import com.example.monkey.user.application.dto.AddressResponseDto;
import com.example.monkey.user.application.dto.AuthLoginResponseDto;
import com.example.monkey.user.application.dto.CaptchaConfigResponseDto;
import com.example.monkey.user.application.dto.UserProfileResponseDto;
import com.example.monkey.user.domain.AddressBook;
import com.example.monkey.user.domain.AuthPrincipal;
import com.example.monkey.user.domain.CaptchaChallengeStore;
import com.example.monkey.user.domain.HumanVerificationService;
import com.example.monkey.user.domain.LoginAttemptPolicy;
import com.example.monkey.user.domain.PasswordCompromiseChecker;
import com.example.monkey.user.domain.PasswordResetChallengeService;
import com.example.monkey.user.domain.PasswordResetDeliveryService;
import com.example.monkey.user.domain.PiiRetentionStore;
import com.example.monkey.user.domain.SessionTokenService;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserMfaVerifier;
import com.example.monkey.user.domain.UserPasswordHasher;
import com.example.monkey.user.domain.UserPasswordPolicy;
import com.example.monkey.user.domain.UserRoles;
import com.example.monkey.user.infrastructure.Address;
import com.example.monkey.user.infrastructure.AddressRepository;
import com.example.monkey.user.infrastructure.ConfigurablePasswordResetDeliveryService;
import com.example.monkey.user.infrastructure.DataInitializer;
import com.example.monkey.user.infrastructure.JpaAddressBook;
import com.example.monkey.user.infrastructure.JpaPiiRetentionStore;
import com.example.monkey.user.infrastructure.JpaUserAccountStore;
import com.example.monkey.user.infrastructure.JwtAuthenticationFilter;
import com.example.monkey.user.infrastructure.JwtTokenService;
import com.example.monkey.user.infrastructure.LoginAttemptService;
import com.example.monkey.user.infrastructure.PasswordHistory;
import com.example.monkey.user.infrastructure.PasswordHistoryRepository;
import com.example.monkey.user.infrastructure.PasswordPolicy;
import com.example.monkey.user.infrastructure.PasswordResetOtpService;
import com.example.monkey.user.infrastructure.Permission;
import com.example.monkey.user.infrastructure.PermissionRepository;
import com.example.monkey.user.infrastructure.PwnedPasswordChecker;
import com.example.monkey.user.infrastructure.RedisCaptchaChallengeStore;
import com.example.monkey.user.infrastructure.Role;
import com.example.monkey.user.infrastructure.RoleRepository;
import com.example.monkey.user.infrastructure.SpringSecurityUserPasswordHasher;
import com.example.monkey.user.infrastructure.TotpService;
import com.example.monkey.user.infrastructure.TurnstileVerifier;
import com.example.monkey.user.infrastructure.User;
import com.example.monkey.user.infrastructure.UserImageReferenceSource;
import com.example.monkey.user.infrastructure.UserRepository;
import com.example.monkey.user.interfaces.AddressController;
import com.example.monkey.user.interfaces.AuthController;
import com.example.monkey.user.interfaces.PrivacyController;
import com.example.monkey.user.interfaces.UserController;
import com.example.monkey.user.interfaces.dto.LoginRequestDto;
import com.example.monkey.user.interfaces.dto.PasswordChangeRequestDto;
import com.example.monkey.user.interfaces.dto.PasswordResetChallengeRequestDto;
import com.example.monkey.user.interfaces.dto.PasswordResetRequestDto;
import com.example.monkey.user.interfaces.dto.RegisterRequestDto;
import com.example.monkey.user.interfaces.dto.UserAvatarRequestDto;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "com.example.monkey", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureBoundaryTest {

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_local_security_package = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.security..");

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_repository_package = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_jpa_repositories = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .areAssignableTo(JpaRepository.class);

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_entity_package = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..entity..");

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_assembler_package = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..assembler..");

    @ArchTest
    static final ArchRule controllers_do_not_parse_client_ip_from_servlet_request = noClasses()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .callMethod(HttpServletRequest.class, "getHeader", String.class)
            .orShould()
            .callMethod(HttpServletRequest.class, "getRemoteAddr");

    @ArchTest
    static final ArchRule controllers_do_not_use_field_injection = noFields()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(RestController.class)
            .should()
            .beAnnotatedWith(Autowired.class);

    @ArchTest
    static final ArchRule services_do_not_use_field_injection = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage("..application..", "..service..")
            .should()
            .beAnnotatedWith(Autowired.class);

    @ArchTest
    static final ArchRule services_do_not_depend_on_infrastructure_package = noClasses()
            .that()
            .resideInAnyPackage("..application..", "..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule dtos_do_not_depend_on_entity_package = noClasses()
            .that()
            .resideInAPackage("..dto..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..entity..");

    @ArchTest
    static final ArchRule dtos_do_not_depend_on_spring_data = noClasses()
            .that()
            .resideInAPackage("..dto..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.data..");

    @ArchTest
    static final ArchRule services_do_not_depend_on_spring_data_web_pagination = noClasses()
            .that()
            .resideInAnyPackage("..application..", "..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.data.domain..");

    @ArchTest
    static final ArchRule services_do_not_depend_on_spring_multipart_uploads = noClasses()
            .that()
            .resideInAnyPackage("..application..", "..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.web.multipart..");

    @ArchTest
    static final ArchRule services_do_not_depend_on_servlet_api = noClasses()
            .that()
            .resideInAnyPackage("..application..", "..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("jakarta.servlet..");

    @ArchTest
    static final ArchRule services_do_not_depend_on_spring_data_redis = noClasses()
            .that()
            .resideInAnyPackage("..application..", "..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.data.redis..");

    @ArchTest
    static final ArchRule services_do_not_depend_on_spring_security_crypto = noClasses()
            .that()
            .resideInAnyPackage("..application..", "..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.security.crypto..");

    @ArchTest
    static final ArchRule services_do_not_depend_on_redisson = noClasses()
            .that()
            .resideInAnyPackage("..application..", "..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.redisson..");

    @ArchTest
    static final ArchRule entities_do_not_depend_on_security_package = noClasses()
            .that()
            .resideInAPackage("..entity..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.security..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule tasks_do_not_depend_on_repository_package = noClasses()
            .that()
            .haveSimpleNameEndingWith("Task")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule tasks_do_not_depend_on_entity_package = noClasses()
            .that()
            .haveSimpleNameEndingWith("Task")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..entity..");

    @ArchTest
    static final ArchRule security_config_does_not_depend_on_repository_or_entity_packages = noClasses()
            .that()
            .haveSimpleName("SecurityConfig")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..repository..", "..entity..");

    @ArchTest
    static final ArchRule jwt_filters_do_not_depend_on_repository_or_entity_packages = noClasses()
            .that()
            .haveSimpleName("JwtAuthenticationFilter")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..repository..", "..entity..");

    @ArchTest
    static final ArchRule jwt_filters_depend_on_session_token_port = noClasses()
            .that()
            .haveSimpleName("JwtAuthenticationFilter")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("JwtTokenService");

    @ArchTest
    static final ArchRule data_initializer_does_not_depend_on_repository_or_entity_packages = noClasses()
            .that()
            .haveSimpleName("DataInitializer")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..repository..", "..entity..");

    @ArchTest
    static final ArchRule data_initializer_stays_in_user_infrastructure = classes()
            .that()
            .haveSimpleName("DataInitializer")
            .should()
            .resideInAPackage("com.example.monkey.user.infrastructure");

    @ArchTest
    static final ArchRule shared_configuration_classes_do_not_depend_on_user_domain_except_security_config = noClasses()
            .that()
            .resideInAPackage("com.example.monkey.shared.infrastructure.config..")
            .and()
            .areNotAssignableTo(SecurityConfig.class)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.user.domain..");

    @ArchTest
    static final ArchRule user_service_does_not_depend_on_security_package = noClasses()
            .that()
            .haveSimpleName("UserService")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.security..");

    @ArchTest
    static final ArchRule data_initializer_does_not_depend_on_security_package = noClasses()
            .that()
            .haveSimpleName("DataInitializer")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.security..");

    @ArchTest
    static final ArchRule captcha_service_does_not_depend_on_security_package = noClasses()
            .that()
            .haveSimpleName("CaptchaService")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.security..");

    @ArchTest
    static final ArchRule security_package_does_not_depend_on_repository_or_entity_packages = noClasses()
            .that()
            .resideInAPackage("..security..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..repository..", "..entity..");

    @ArchTest
    static final ArchRule root_privacy_infrastructure_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.infrastructure.privacy..");

    @ArchTest
    static final ArchRule root_observability_domain_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.domain.observability..");

    @ArchTest
    static final ArchRule root_domain_user_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.domain.user..");

    @ArchTest
    static final ArchRule root_observability_infrastructure_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.infrastructure.observability..");

    @ArchTest
    static final ArchRule root_storage_domain_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.domain.storage..");

    @ArchTest
    static final ArchRule root_storage_infrastructure_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.infrastructure.storage..");

    @ArchTest
    static final ArchRule root_task_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.task..");

    @ArchTest
    static final ArchRule root_assembler_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.assembler..");

    @ArchTest
    static final ArchRule root_service_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.service..");

    @ArchTest
    static final ArchRule root_dto_package_is_empty = noClasses().should().resideInAPackage("com.example.monkey.dto..");

    @ArchTest
    static final ArchRule root_entity_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.entity..");

    @ArchTest
    static final ArchRule root_repository_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.repository..");

    @ArchTest
    static final ArchRule root_controller_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.controller..");

    @ArchTest
    static final ArchRule root_config_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.config..");

    @ArchTest
    static final ArchRule root_interceptor_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.interceptor..");

    @ArchTest
    static final ArchRule root_util_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.util..");

    @ArchTest
    static final ArchRule root_security_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.security..");

    @ArchTest
    static final ArchRule root_domain_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.domain..");

    @ArchTest
    static final ArchRule root_infrastructure_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.infrastructure..");

    @ArchTest
    static final ArchRule shared_web_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.shared.web..");

    @ArchTest
    static final ArchRule shared_api_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.shared.api..");

    @ArchTest
    static final ArchRule shared_exception_package_is_empty =
            noClasses().should().resideInAPackage("com.example.monkey.shared.exception..");

    @ArchTest
    static final ArchRule privacy_infrastructure_does_not_depend_on_security_package = noClasses()
            .that()
            .resideInAPackage("..infrastructure.privacy..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.security..");

    @ArchTest
    static final ArchRule bounded_context_classes_stay_inside_ws3_layers = classes()
            .that()
            .resideInAnyPackage(
                    "com.example.monkey.admin..",
                    "com.example.monkey.cart..",
                    "com.example.monkey.inventory..",
                    "com.example.monkey.marketing..",
                    "com.example.monkey.order..",
                    "com.example.monkey.payment..",
                    "com.example.monkey.product..",
                    "com.example.monkey.shared..",
                    "com.example.monkey.user..")
            .should()
            .resideInAnyPackage(
                    "com.example.monkey.admin.domain..",
                    "com.example.monkey.admin.application..",
                    "com.example.monkey.admin.infrastructure..",
                    "com.example.monkey.admin.interfaces..",
                    "com.example.monkey.cart.domain..",
                    "com.example.monkey.cart.application..",
                    "com.example.monkey.cart.infrastructure..",
                    "com.example.monkey.cart.interfaces..",
                    "com.example.monkey.inventory.domain..",
                    "com.example.monkey.inventory.application..",
                    "com.example.monkey.inventory.infrastructure..",
                    "com.example.monkey.inventory.interfaces..",
                    "com.example.monkey.marketing.domain..",
                    "com.example.monkey.marketing.application..",
                    "com.example.monkey.marketing.infrastructure..",
                    "com.example.monkey.marketing.interfaces..",
                    "com.example.monkey.order.domain..",
                    "com.example.monkey.order.application..",
                    "com.example.monkey.order.infrastructure..",
                    "com.example.monkey.order.interfaces..",
                    "com.example.monkey.payment.domain..",
                    "com.example.monkey.payment.application..",
                    "com.example.monkey.payment.infrastructure..",
                    "com.example.monkey.payment.interfaces..",
                    "com.example.monkey.product.domain..",
                    "com.example.monkey.product.application..",
                    "com.example.monkey.product.infrastructure..",
                    "com.example.monkey.product.interfaces..",
                    "com.example.monkey.shared.domain..",
                    "com.example.monkey.shared.application..",
                    "com.example.monkey.shared.infrastructure..",
                    "com.example.monkey.shared.interfaces..",
                    "com.example.monkey.user.domain..",
                    "com.example.monkey.user.application..",
                    "com.example.monkey.user.infrastructure..",
                    "com.example.monkey.user.interfaces..");

    @Test
    void authenticationIdentityTypesStayInUserDomain() {
        assertThat(AuthPrincipal.class.getPackageName()).isEqualTo("com.example.monkey.user.domain");
        assertThat(UserRoles.class.getPackageName()).isEqualTo("com.example.monkey.user.domain");
    }

    @Test
    void sharedSecurityIdentityContractsStayInExpectedLayers() {
        assertThat(AuthenticatedPrincipals.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.security");
        assertThat(SessionUser.class.getPackageName()).isEqualTo("com.example.monkey.shared.application.security");
        assertThat(JwtTokenPair.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.security");
    }

    @ArchTest
    static final ArchRule domain_does_not_depend_on_framework_or_adapter_packages = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.servlet..",
                    "jakarta.persistence..",
                    "org.hibernate..",
                    "..controller..",
                    "..repository..",
                    "..service..",
                    "..entity..",
                    "..config..");

    @Test
    void adminStatsSliceUsesWs3BoundedContextLayers() {
        assertThat(AdminStatsReader.class.getPackageName()).isEqualTo("com.example.monkey.admin.domain");
        assertThat(StatsService.class.getPackageName()).isEqualTo("com.example.monkey.admin.application");
        assertThat(StatsResponseDto.class.getPackageName()).isEqualTo("com.example.monkey.admin.application.dto");
        assertThat(JpaAdminStatsReader.class.getPackageName()).isEqualTo("com.example.monkey.admin.infrastructure");
        assertThat(StatsController.class.getPackageName()).isEqualTo("com.example.monkey.admin.interfaces");
        assertThat(AuditTraceRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.admin.interfaces.dto");
        assertThat(StatsQueryRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.admin.interfaces.dto");
    }

    @Test
    void productCatalogSliceUsesWs3BoundedContextLayers() {
        assertThat(ProductCatalog.class.getPackageName()).isEqualTo("com.example.monkey.product.domain");
        assertThat(MonkeyService.class.getPackageName()).isEqualTo("com.example.monkey.product.application");
        assertThat(MonkeyRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.product.application.dto");
        assertThat(MonkeyResponseDto.class.getPackageName()).isEqualTo("com.example.monkey.product.application.dto");
        assertThat(ProductPageQuery.class.getPackageName()).isEqualTo("com.example.monkey.product.application.dto");
        assertThat(JpaProductCatalog.class.getPackageName()).isEqualTo("com.example.monkey.product.infrastructure");
        assertThat(Monkey.class.getPackageName()).isEqualTo("com.example.monkey.product.infrastructure");
        assertThat(MonkeyRepository.class.getPackageName()).isEqualTo("com.example.monkey.product.infrastructure");
        assertThat(MonkeyController.class.getPackageName()).isEqualTo("com.example.monkey.product.interfaces");
    }

    @Test
    void inventorySliceUsesWs2BoundedContextLayers() {
        assertThat(InventoryStore.class.getPackageName()).isEqualTo("com.example.monkey.inventory.domain");
        assertThat(InventoryLockManager.class.getPackageName()).isEqualTo("com.example.monkey.inventory.domain");
        assertThat(WarehouseStock.class.getPackageName()).isEqualTo("com.example.monkey.inventory.domain");
        assertThat(InventoryApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.application");
        assertThat(InventoryCompensateRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.application.dto");
        assertThat(InventoryReconciliationResponseDto.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.application.dto");
        assertThat(InventoryReservationResponseDto.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.application.dto");
        assertThat(InventoryReserveRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.application.dto");
        assertThat(WarehouseStockResponseDto.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.application.dto");
        assertThat(JpaInventoryStore.class.getPackageName()).isEqualTo("com.example.monkey.inventory.infrastructure");
        assertThat(RedissonInventoryLockManager.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.infrastructure");
        assertThat(InventoryReservationEntity.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.infrastructure");
        assertThat(InventoryReservationRepository.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.infrastructure");
        assertThat(InventoryStock.class.getPackageName()).isEqualTo("com.example.monkey.inventory.infrastructure");
        assertThat(InventoryStockLedger.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.infrastructure");
        assertThat(InventoryStockLedgerRepository.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.infrastructure");
        assertThat(InventoryStockRepository.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.infrastructure");
        assertThat(InventoryWarehouse.class.getPackageName()).isEqualTo("com.example.monkey.inventory.infrastructure");
        assertThat(InventoryWarehouseRepository.class.getPackageName())
                .isEqualTo("com.example.monkey.inventory.infrastructure");
        assertThat(InventoryController.class.getPackageName()).isEqualTo("com.example.monkey.inventory.interfaces");
    }

    @Test
    void marketingSliceUsesWs3BoundedContextLayers() {
        assertThat(MarketingStore.class.getPackageName()).isEqualTo("com.example.monkey.marketing.domain");
        assertThat(MarketingLockManager.class.getPackageName()).isEqualTo("com.example.monkey.marketing.domain");
        assertThat(MarketingIdempotencyStore.class.getPackageName()).isEqualTo("com.example.monkey.marketing.domain");
        assertThat(CouponDefinition.class.getPackageName()).isEqualTo("com.example.monkey.marketing.domain");
        assertThat(SeckillActivity.class.getPackageName()).isEqualTo("com.example.monkey.marketing.domain");
        assertThat(MarketingApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.marketing.application");
        assertThat(CouponClaimRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.marketing.application.dto");
        assertThat(SeckillRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.marketing.application.dto");
        assertThat(GroupBuyJoinRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.marketing.application.dto");
        assertThat(JpaMarketingStore.class.getPackageName()).isEqualTo("com.example.monkey.marketing.infrastructure");
        assertThat(RedisMarketingIdempotencyStore.class.getPackageName())
                .isEqualTo("com.example.monkey.marketing.infrastructure");
        assertThat(RedissonMarketingLockManager.class.getPackageName())
                .isEqualTo("com.example.monkey.marketing.infrastructure");
        assertThat(MarketingCouponEntity.class.getPackageName())
                .isEqualTo("com.example.monkey.marketing.infrastructure");
        assertThat(MarketingSeckillActivityEntity.class.getPackageName())
                .isEqualTo("com.example.monkey.marketing.infrastructure");
        assertThat(MarketingCouponRepository.class.getPackageName())
                .isEqualTo("com.example.monkey.marketing.infrastructure");
        assertThat(MarketingController.class.getPackageName()).isEqualTo("com.example.monkey.marketing.interfaces");
    }

    @Test
    void paymentSliceUsesWs6BoundedContextLayers() {
        assertThat(PaymentStore.class.getPackageName()).isEqualTo("com.example.monkey.payment.domain");
        assertThat(PaymentGateway.class.getPackageName()).isEqualTo("com.example.monkey.payment.domain");
        assertThat(PaymentCallbackReplayGuard.class.getPackageName()).isEqualTo("com.example.monkey.payment.domain");
        assertThat(PaymentTransitionResolver.class.getPackageName()).isEqualTo("com.example.monkey.payment.domain");
        assertThat(PaymentOrder.class.getPackageName()).isEqualTo("com.example.monkey.payment.domain");
        assertThat(PaymentLedgerEntry.class.getPackageName()).isEqualTo("com.example.monkey.payment.domain");
        assertThat(PaymentReconciliationReport.class.getPackageName()).isEqualTo("com.example.monkey.payment.domain");
        assertThat(PaymentApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.payment.application");
        assertThat(PaymentCreateRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.payment.application.dto");
        assertThat(PaymentCallbackRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.payment.application.dto");
        assertThat(PaymentRefundRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.payment.application.dto");
        assertThat(PaymentReconciliationRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.payment.application.dto");
        assertThat(PaymentResponseDto.class.getPackageName()).isEqualTo("com.example.monkey.payment.application.dto");
        assertThat(JpaPaymentStore.class.getPackageName()).isEqualTo("com.example.monkey.payment.infrastructure");
        assertThat(SandboxPaymentGateway.class.getPackageName()).isEqualTo("com.example.monkey.payment.infrastructure");
        assertThat(RedisPaymentCallbackReplayGuard.class.getPackageName())
                .isEqualTo("com.example.monkey.payment.infrastructure");
        assertThat(SpringStateMachinePaymentTransitionResolver.class.getPackageName())
                .isEqualTo("com.example.monkey.payment.infrastructure");
        assertThat(PaymentOrderEntity.class.getPackageName()).isEqualTo("com.example.monkey.payment.infrastructure");
        assertThat(PaymentLedgerEntity.class.getPackageName()).isEqualTo("com.example.monkey.payment.infrastructure");
        assertThat(PaymentCallbackLogEntity.class.getPackageName())
                .isEqualTo("com.example.monkey.payment.infrastructure");
        assertThat(PaymentReconciliationReportEntity.class.getPackageName())
                .isEqualTo("com.example.monkey.payment.infrastructure");
        assertThat(PaymentController.class.getPackageName()).isEqualTo("com.example.monkey.payment.interfaces");
    }

    @Test
    void logisticsSliceUsesWs7BoundedContextLayers() {
        assertThat(LogisticsStore.class.getPackageName()).isEqualTo("com.example.monkey.logistics.domain");
        assertThat(LogisticsGateway.class.getPackageName()).isEqualTo("com.example.monkey.logistics.domain");
        assertThat(LogisticsWebhookReplayGuard.class.getPackageName()).isEqualTo("com.example.monkey.logistics.domain");
        assertThat(LogisticsTransitionResolver.class.getPackageName()).isEqualTo("com.example.monkey.logistics.domain");
        assertThat(AddressParser.class.getPackageName()).isEqualTo("com.example.monkey.logistics.domain");
        assertThat(LogisticsTracking.class.getPackageName()).isEqualTo("com.example.monkey.logistics.domain");
        assertThat(TrackingEventRecord.class.getPackageName()).isEqualTo("com.example.monkey.logistics.domain");
        assertThat(FreightTemplate.class.getPackageName()).isEqualTo("com.example.monkey.logistics.domain");
        assertThat(LogisticsApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.application");
        assertThat(ShipmentCreateRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.application.dto");
        assertThat(FreightQuoteRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.application.dto");
        assertThat(TrackingWebhookRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.application.dto");
        assertThat(LogisticsTrackingResponseDto.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.application.dto");
        assertThat(JpaLogisticsStore.class.getPackageName()).isEqualTo("com.example.monkey.logistics.infrastructure");
        assertThat(SandboxLogisticsGateway.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.infrastructure");
        assertThat(RedisLogisticsWebhookReplayGuard.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.infrastructure");
        assertThat(SpringStateMachineLogisticsTransitionResolver.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.infrastructure");
        assertThat(RuleBasedAddressParser.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.infrastructure");
        assertThat(LogisticsTrackingEntity.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.infrastructure");
        assertThat(LogisticsTrackingEventEntity.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.infrastructure");
        assertThat(LogisticsWebhookLogEntity.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.infrastructure");
        assertThat(FreightTemplateEntity.class.getPackageName())
                .isEqualTo("com.example.monkey.logistics.infrastructure");
        assertThat(LogisticsController.class.getPackageName()).isEqualTo("com.example.monkey.logistics.interfaces");
    }

    @Test
    void cartSliceUsesWs4BoundedContextLayers() {
        assertThat(CartStore.class.getPackageName()).isEqualTo("com.example.monkey.cart.domain");
        assertThat(CartCheckoutStore.class.getPackageName()).isEqualTo("com.example.monkey.cart.domain");
        assertThat(CartLockManager.class.getPackageName()).isEqualTo("com.example.monkey.cart.domain");
        assertThat(CartCatalogReader.class.getPackageName()).isEqualTo("com.example.monkey.cart.domain");
        assertThat(CartItem.class.getPackageName()).isEqualTo("com.example.monkey.cart.domain");
        assertThat(CartApplicationService.class.getPackageName()).isEqualTo("com.example.monkey.cart.application");
        assertThat(CartAddItemRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.cart.application.dto");
        assertThat(CartCheckoutRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.cart.application.dto");
        assertThat(CartResponseDto.class.getPackageName()).isEqualTo("com.example.monkey.cart.application.dto");
        assertThat(JpaCartCheckoutStore.class.getPackageName()).isEqualTo("com.example.monkey.cart.infrastructure");
        assertThat(JpaCartCatalogReader.class.getPackageName()).isEqualTo("com.example.monkey.cart.infrastructure");
        assertThat(RedisCartStore.class.getPackageName()).isEqualTo("com.example.monkey.cart.infrastructure");
        assertThat(RedissonCartLockManager.class.getPackageName()).isEqualTo("com.example.monkey.cart.infrastructure");
        assertThat(CartCheckoutEntity.class.getPackageName()).isEqualTo("com.example.monkey.cart.infrastructure");
        assertThat(CartController.class.getPackageName()).isEqualTo("com.example.monkey.cart.interfaces");
    }

    @Test
    void userAddressSliceUsesWs3BoundedContextLayers() {
        assertThat(AddressBook.class.getPackageName()).isEqualTo("com.example.monkey.user.domain");
        assertThat(AddressService.class.getPackageName()).isEqualTo("com.example.monkey.user.application");
        assertThat(AddressApplicationService.class.getPackageName()).isEqualTo("com.example.monkey.user.application");
        assertThat(AddressPageQuery.class.getPackageName()).isEqualTo("com.example.monkey.user.application.dto");
        assertThat(AddressRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.user.application.dto");
        assertThat(AddressResponseDto.class.getPackageName()).isEqualTo("com.example.monkey.user.application.dto");
        assertThat(JpaAddressBook.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(Address.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(AddressRepository.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(AddressController.class.getPackageName()).isEqualTo("com.example.monkey.user.interfaces");
    }

    @Test
    void userAccountSliceUsesWs3BoundedContextLayers() {
        assertThat(UserAccountStore.class.getPackageName()).isEqualTo("com.example.monkey.user.domain");
        assertThat(UserPasswordHasher.class.getPackageName()).isEqualTo("com.example.monkey.user.domain");
        assertThat(DataInitializer.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(JpaUserAccountStore.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(User.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(UserRepository.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(Role.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(RoleRepository.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(Permission.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(PermissionRepository.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(PasswordHistory.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(PasswordHistoryRepository.class.getPackageName())
                .isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(SpringSecurityUserPasswordHasher.class.getPackageName())
                .isEqualTo("com.example.monkey.user.infrastructure");
    }

    @Test
    void userLoginAttemptSliceUsesWs3BoundedContextLayers() {
        assertThat(LoginAttemptService.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
    }

    @Test
    void userPasswordResetAndMfaSliceUsesWs3BoundedContextLayers() {
        assertThat(PasswordPolicy.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(PwnedPasswordChecker.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(PasswordResetOtpService.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(ConfigurablePasswordResetDeliveryService.class.getPackageName())
                .isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(TotpService.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
    }

    @Test
    void userJwtSessionSliceUsesWs3BoundedContextLayers() {
        assertThat(AuthDtoAssembler.class.getPackageName()).isEqualTo("com.example.monkey.user.application");
        assertThat(AuthResponseService.class.getPackageName()).isEqualTo("com.example.monkey.user.application");
        assertThat(AuthenticatedUserPrincipal.class.getPackageName()).isEqualTo("com.example.monkey.user.application");
        assertThat(AuthenticationApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.user.application");
        assertThat(LoginAttemptApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.user.application");
        assertThat(LoginApplicationService.class.getPackageName()).isEqualTo("com.example.monkey.user.application");
        assertThat(RefreshTokenApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.user.application");
        assertThat(RegistrationApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.user.application");
        assertThat(PasswordResetChallengeApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.user.application");
        assertThat(PasswordResetApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.user.application");
        assertThat(SessionTokenApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.user.application");
        assertThat(SessionTokenPair.class.getPackageName()).isEqualTo("com.example.monkey.shared.application.security");
        assertThat(SessionTokenService.class.getPackageName()).isEqualTo("com.example.monkey.user.domain");
        assertThat(JwtTokenService.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(JwtAuthenticationFilter.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
    }

    @Test
    void userCaptchaSliceUsesWs3BoundedContextLayers() {
        assertThat(CaptchaChallenge.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.security");
        assertThat(CaptchaChallengeResult.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.security");
        assertThat(CaptchaChallengeStore.class.getPackageName()).isEqualTo("com.example.monkey.user.domain");
        assertThat(HumanVerificationService.class.getPackageName()).isEqualTo("com.example.monkey.user.domain");
        assertThat(CaptchaService.class.getPackageName()).isEqualTo("com.example.monkey.user.application");
        assertThat(RedisCaptchaChallengeStore.class.getPackageName())
                .isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(TurnstileVerifier.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
    }

    @Test
    void sharedObservabilitySliceUsesWs3BoundedContextLayers() {
        assertThat(AuditLogStore.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.observability");
        assertThat(VisitLogRecorder.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.observability");
        assertThat(AuditService.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.observability");
        assertThat(VisitMetricsService.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.observability");
        assertThat(AuditTraceEventDto.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.observability.dto");
        assertThat(JpaAuditLogStore.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.observability");
        assertThat(JpaVisitLogRecorder.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.observability");
        assertThat(AuditLog.class.getPackageName()).isEqualTo("com.example.monkey.shared.infrastructure.observability");
        assertThat(VisitLog.class.getPackageName()).isEqualTo("com.example.monkey.shared.infrastructure.observability");
        assertThat(AuditLogRepository.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.observability");
        assertThat(VisitLogRepository.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.observability");
    }

    @Test
    void sharedStorageSliceUsesWs3BoundedContextLayers() {
        assertThat(ImageReferenceService.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.storage");
        assertThat(ImageUsageChecker.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.storage");
        assertThat(MalwareDetectedException.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.domain.storage");
        assertThat(ObjectStorageKey.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.storage");
        assertThat(ObjectStorageService.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.storage");
        assertThat(StoredImageReferenceReader.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.domain.storage");
        assertThat(StoredImageReferenceSource.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.domain.storage");
        assertThat(UploadFileContent.class.getPackageName()).isEqualTo("com.example.monkey.shared.application.storage");
        assertThat(VirusScanner.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.storage");
        assertThat(FileService.class.getPackageName()).isEqualTo("com.example.monkey.shared.application.storage");
        assertThat(ImageCleanupService.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.storage");
        assertThat(ImageVariantService.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.storage");
        assertThat(UploadDtoAssembler.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.storage");
        assertThat(PresignedGetUrlResponseDto.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.storage.dto");
        assertThat(PresignedUploadResponseDto.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.storage.dto");
        assertThat(UploadResponseDto.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.storage.dto");
        assertThat(ClamAvVirusScanner.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.storage");
        assertThat(ImageTask.class.getPackageName()).isEqualTo("com.example.monkey.shared.infrastructure.storage");
        assertThat(InMemoryImageReferenceService.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.storage");
        assertThat(CompositeImageUsageChecker.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.storage");
        assertThat(CompositeStoredImageReferenceReader.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.storage");
        assertThat(OrderImageReferenceSource.class.getPackageName())
                .isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(ProductImageReferenceSource.class.getPackageName())
                .isEqualTo("com.example.monkey.product.infrastructure");
        assertThat(UserImageReferenceSource.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(LocalObjectStorageService.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.storage");
        assertThat(MinioObjectStorageService.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.storage");
        assertThat(NoOpVirusScanner.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.storage");
        assertThat(RedisImageReferenceService.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.storage");
        assertThat(UploadController.class.getPackageName()).isEqualTo("com.example.monkey.shared.interfaces.storage");
        assertThat(PresignedGetUrlRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.interfaces.storage.dto");
        assertThat(PresignedUploadRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.interfaces.storage.dto");
        assertThat(UploadFileRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.interfaces.storage.dto");
        assertThat(UploadRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.interfaces.storage.dto");
    }

    @Test
    void sharedPrivacySliceUsesWs3BoundedContextLayers() {
        assertThat(PhoneBlindIndexTarget.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.privacy");
        assertThat(PiiCryptoService.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.privacy");
        assertThat(PiiKeyMaterialProvider.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.privacy");
        assertThat(EncryptedStringAttributeConverter.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.privacy");
        assertThat(PiiBlindIndexEntityListener.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.privacy");
    }

    @Test
    void sharedDtoContractsStayInSharedApplication() {
        assertThat(PageResponseDto.class.getPackageName()).isEqualTo("com.example.monkey.shared.application.dto");
        assertThat(Result.class.getPackageName()).isEqualTo("com.example.monkey.shared.interfaces.dto");
    }

    @Test
    void sharedExceptionContractsStayInDomainAndHttpMappingStaysInInterfaces() {
        assertThat(ErrorCode.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.exception");
        assertThat(BusinessException.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.exception");
        assertThat(ErrorHttpStatuses.class.getPackageName()).isEqualTo("com.example.monkey.shared.interfaces.web");
    }

    @Test
    void sharedRateLimitSliceUsesWs3BoundedContextLayers() {
        assertThat(ApiRateLimiter.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.security");
        assertThat(RateLimitPolicy.class.getPackageName()).isEqualTo("com.example.monkey.shared.domain.security");
        assertThat(ApiRateLimitApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.security");
        assertThat(ApiRateLimitOperation.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.security");
        assertThat(ApiRateLimitResult.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.application.security");
        assertThat(ApiRateLimitService.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.infrastructure.security");
        assertThat(ApiRateLimitFilter.class.getPackageName())
                .isEqualTo("com.example.monkey.shared.interfaces.security");
    }

    @Test
    void userProfileSliceUsesWs3BoundedContextLayers() {
        assertThat(UserService.class.getPackageName()).isEqualTo("com.example.monkey.user.application");
        assertThat(UserProfileApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.user.application");
        assertThat(PasswordChangeApplicationService.class.getPackageName())
                .isEqualTo("com.example.monkey.user.application");
        assertThat(UserDtoAssembler.class.getPackageName()).isEqualTo("com.example.monkey.user.application");
        assertThat(AuthLoginResponseDto.class.getPackageName()).isEqualTo("com.example.monkey.user.application.dto");
        assertThat(CaptchaConfigResponseDto.class.getPackageName())
                .isEqualTo("com.example.monkey.user.application.dto");
        assertThat(UserProfileResponseDto.class.getPackageName()).isEqualTo("com.example.monkey.user.application.dto");
        assertThat(UserController.class.getPackageName()).isEqualTo("com.example.monkey.user.interfaces");
        assertThat(AuthController.class.getPackageName()).isEqualTo("com.example.monkey.user.interfaces");
        assertThat(LoginRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.user.interfaces.dto");
        assertThat(PasswordChangeRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.user.interfaces.dto");
        assertThat(PasswordResetChallengeRequestDto.class.getPackageName())
                .isEqualTo("com.example.monkey.user.interfaces.dto");
        assertThat(PasswordResetRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.user.interfaces.dto");
        assertThat(RegisterRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.user.interfaces.dto");
        assertThat(UserAvatarRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.user.interfaces.dto");
    }

    @Test
    void userPrivacySliceUsesWs3BoundedContextLayers() {
        assertThat(PiiRetentionStore.class.getPackageName()).isEqualTo("com.example.monkey.user.domain");
        assertThat(PiiRetentionService.class.getPackageName()).isEqualTo("com.example.monkey.user.application");
        assertThat(PrivacyApplicationService.class.getPackageName()).isEqualTo("com.example.monkey.user.application");
        assertThat(JpaPiiRetentionStore.class.getPackageName()).isEqualTo("com.example.monkey.user.infrastructure");
        assertThat(PrivacyController.class.getPackageName()).isEqualTo("com.example.monkey.user.interfaces");
    }

    @Test
    void orderSliceUsesWs3BoundedContextLayers() {
        assertThat(OrderStore.class.getPackageName()).isEqualTo("com.example.monkey.order.domain");
        assertThat(OrderFulfillmentStore.class.getPackageName()).isEqualTo("com.example.monkey.order.domain");
        assertThat(OrderService.class.getPackageName()).isEqualTo("com.example.monkey.order.application");
        assertThat(OrderApplicationService.class.getPackageName()).isEqualTo("com.example.monkey.order.application");
        assertThat(OrderPageQuery.class.getPackageName()).isEqualTo("com.example.monkey.order.application.dto");
        assertThat(OrderResponseDto.class.getPackageName()).isEqualTo("com.example.monkey.order.application.dto");
        assertThat(OrderIdempotencyService.class.getPackageName()).isEqualTo("com.example.monkey.order.application");
        assertThat(OrderOwnershipService.class.getPackageName()).isEqualTo("com.example.monkey.order.application");
        assertThat(BusinessMetricsService.class.getPackageName())
                .isEqualTo("com.example.monkey.order.application.observability");
        assertThat(JpaOrderStore.class.getPackageName()).isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(JpaOrderFulfillmentStore.class.getPackageName())
                .isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(Order.class.getPackageName()).isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(OrderFulfillmentItemEntity.class.getPackageName())
                .isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(OrderShipmentBatchEntity.class.getPackageName())
                .isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(OrderReviewEntity.class.getPackageName()).isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(OrderRepository.class.getPackageName()).isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(IdempotencyRecord.class.getPackageName()).isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(IdempotencyRecordRepository.class.getPackageName())
                .isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(StockLog.class.getPackageName()).isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(StockLogRepository.class.getPackageName()).isEqualTo("com.example.monkey.order.infrastructure");
        assertThat(OrderController.class.getPackageName()).isEqualTo("com.example.monkey.order.interfaces");
        assertThat(OrderOwnership.class.getPackageName()).isEqualTo("com.example.monkey.order.interfaces");
        assertThat(CreateOrderRequestDto.class.getPackageName()).isEqualTo("com.example.monkey.order.interfaces.dto");
    }

    @ArchTest
    static final ArchRule application_layer_does_not_depend_on_infrastructure_or_interfaces = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..infrastructure..", "..interfaces..", "..repository..", "..entity..");

    @ArchTest
    static final ArchRule interfaces_layer_does_not_depend_on_infrastructure_or_persistence = noClasses()
            .that()
            .resideInAPackage("..interfaces..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..infrastructure..", "..repository..", "..entity..");

    @ArchTest
    static final ArchRule shared_interfaces_do_not_depend_on_feature_domain_packages = noClasses()
            .that()
            .resideInAPackage("com.example.monkey.shared.interfaces..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.example.monkey.admin.domain..",
                    "com.example.monkey.cart.domain..",
                    "com.example.monkey.inventory.domain..",
                    "com.example.monkey.marketing.domain..",
                    "com.example.monkey.order.domain..",
                    "com.example.monkey.product.domain..",
                    "com.example.monkey.user.domain..");

    @ArchTest
    static final ArchRule non_user_interfaces_do_not_depend_on_user_domain = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.example.monkey.admin.interfaces..",
                    "com.example.monkey.cart.interfaces..",
                    "com.example.monkey.order.interfaces..",
                    "com.example.monkey.product.interfaces..",
                    "com.example.monkey.shared.interfaces..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.user.domain..");

    @ArchTest
    static final ArchRule order_number_generator_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(OrderNumberGenerator.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule order_ownership_checker_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(OrderOwnershipChecker.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule order_ownership_expression_adapters_stay_out_of_security_package =
            classes().that().haveSimpleName("OrderOwnership").should().resideOutsideOfPackage("..security..");

    @ArchTest
    static final ArchRule order_lock_manager_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(OrderLockManager.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule order_transition_resolver_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(OrderTransitionResolver.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule pending_order_counter_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(PendingOrderCounter.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule order_idempotency_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(OrderIdempotencyStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule order_idempotency_key_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(OrderIdempotencyKeyStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule order_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(OrderStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule order_fulfillment_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(OrderFulfillmentStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule product_interfaces_do_not_depend_on_product_domain = noClasses()
            .that()
            .resideInAPackage("com.example.monkey.product.interfaces..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.product.domain..");

    @ArchTest
    static final ArchRule inventory_interfaces_do_not_depend_on_inventory_domain = noClasses()
            .that()
            .resideInAPackage("com.example.monkey.inventory.interfaces..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.inventory.domain..");

    @ArchTest
    static final ArchRule marketing_interfaces_do_not_depend_on_marketing_domain = noClasses()
            .that()
            .resideInAPackage("com.example.monkey.marketing.interfaces..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.marketing.domain..");

    @ArchTest
    static final ArchRule cart_interfaces_do_not_depend_on_cart_domain = noClasses()
            .that()
            .resideInAPackage("com.example.monkey.cart.interfaces..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.cart.domain..");

    @ArchTest
    static final ArchRule payment_interfaces_do_not_depend_on_payment_domain = noClasses()
            .that()
            .resideInAPackage("com.example.monkey.payment.interfaces..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.payment.domain..");

    @ArchTest
    static final ArchRule address_controller_does_not_depend_on_user_domain = noClasses()
            .that()
            .haveSimpleName("AddressController")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.user.domain..");

    @ArchTest
    static final ArchRule auth_controller_does_not_depend_on_user_domain = noClasses()
            .that()
            .haveSimpleName("AuthController")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.user.domain..");

    @ArchTest
    static final ArchRule user_controller_does_not_depend_on_domain_exception_contracts = noClasses()
            .that()
            .haveSimpleName("UserController")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.shared.domain.exception..");

    @ArchTest
    static final ArchRule order_controller_does_not_depend_on_order_domain = noClasses()
            .that()
            .haveSimpleName("OrderController")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.order.domain..");

    @ArchTest
    static final ArchRule order_interfaces_do_not_depend_on_order_domain = noClasses()
            .that()
            .resideInAPackage("com.example.monkey.order.interfaces..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.order.domain..");

    @ArchTest
    static final ArchRule order_controller_does_not_depend_on_domain_exception_contracts = noClasses()
            .that()
            .haveSimpleName("OrderController")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.shared.domain.exception..");

    @ArchTest
    static final ArchRule product_catalog_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(ProductCatalog.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule inventory_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(InventoryStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule inventory_lock_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(InventoryLockManager.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule marketing_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(MarketingStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule marketing_lock_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(MarketingLockManager.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule marketing_idempotency_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(MarketingIdempotencyStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule payment_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(PaymentStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule payment_gateway_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(PaymentGateway.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule payment_callback_guard_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(PaymentCallbackReplayGuard.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule payment_transition_resolver_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(PaymentTransitionResolver.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule cart_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(CartStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule cart_checkout_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(CartCheckoutStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule cart_lock_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(CartLockManager.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule cart_catalog_reader_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(CartCatalogReader.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule address_book_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(AddressBook.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule user_account_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(UserAccountStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule user_password_hasher_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(UserPasswordHasher.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule user_password_policy_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(UserPasswordPolicy.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule user_mfa_verifier_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(UserMfaVerifier.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule visit_log_recorder_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(VisitLogRecorder.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule admin_stats_reader_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(AdminStatsReader.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule audit_log_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(AuditLogStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule object_storage_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(ObjectStorageService.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule upload_file_content_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(UploadFileContent.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule upload_controller_does_not_depend_on_domain_exception_contracts = noClasses()
            .that()
            .haveSimpleName("UploadController")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.shared.domain.exception..");

    @ArchTest
    static final ArchRule shared_interfaces_do_not_depend_on_domain_storage_contracts = noClasses()
            .that()
            .resideInAPackage("com.example.monkey.shared.interfaces..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.shared.domain.storage..");

    @ArchTest
    static final ArchRule shared_storage_infrastructure_does_not_depend_on_feature_infrastructure = noClasses()
            .that()
            .resideInAPackage("com.example.monkey.shared.infrastructure.storage..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.example.monkey.admin.infrastructure..",
                    "com.example.monkey.order.infrastructure..",
                    "com.example.monkey.product.infrastructure..",
                    "com.example.monkey.user.infrastructure..");

    @ArchTest
    static final ArchRule image_reference_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(ImageReferenceService.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule image_usage_checker_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(ImageUsageChecker.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule stored_image_reference_reader_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(StoredImageReferenceReader.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule stored_image_reference_sources_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(StoredImageReferenceSource.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule pii_retention_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(PiiRetentionStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule human_verification_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(HumanVerificationService.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule captcha_challenge_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(CaptchaChallengeStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule session_token_service_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(SessionTokenService.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule session_token_service_adapters_stay_out_of_security_package = classes()
            .that()
            .areAssignableTo(SessionTokenService.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..security..");

    @ArchTest
    static final ArchRule jwt_authentication_filters_stay_out_of_security_package =
            classes().that().haveSimpleName("JwtAuthenticationFilter").should().resideOutsideOfPackage("..security..");

    @ArchTest
    static final ArchRule password_compromise_checker_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(PasswordCompromiseChecker.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule password_reset_delivery_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(PasswordResetDeliveryService.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule login_attempt_policy_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(LoginAttemptPolicy.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule password_reset_challenge_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(PasswordResetChallengeService.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule api_rate_limiter_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(ApiRateLimiter.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule api_rate_limiter_adapters_stay_out_of_security_package = classes()
            .that()
            .areAssignableTo(ApiRateLimiter.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("com.example.monkey.security..");

    @ArchTest
    static final ArchRule api_rate_limit_filters_stay_out_of_security_package = classes()
            .that()
            .haveSimpleName("ApiRateLimitFilter")
            .should()
            .resideOutsideOfPackage("com.example.monkey.security..");

    @ArchTest
    static final ArchRule api_rate_limit_filter_does_not_depend_on_domain_rate_limit_contracts = noClasses()
            .that()
            .haveSimpleName("ApiRateLimitFilter")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.shared.domain.security..");

    @ArchTest
    static final ArchRule virus_scanner_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(VirusScanner.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule captcha_http_does_not_depend_on_domain_security_contracts = noClasses()
            .that()
            .haveSimpleName("CaptchaHttp")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.shared.domain.security..");

    @ArchTest
    static final ArchRule session_token_transport_does_not_depend_on_domain_security_contracts = noClasses()
            .that()
            .haveSimpleName("SessionTokenTransport")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.shared.domain.security..");
}

package com.example.monkey.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.domain.admin.AdminStatsReader;
import com.example.monkey.domain.observability.AuditLogStore;
import com.example.monkey.domain.observability.VisitLogRecorder;
import com.example.monkey.domain.order.OrderIdempotencyStore;
import com.example.monkey.domain.order.OrderNumberGenerator;
import com.example.monkey.domain.order.OrderOwnershipChecker;
import com.example.monkey.domain.order.OrderStore;
import com.example.monkey.domain.order.OrderTransitionResolver;
import com.example.monkey.domain.order.PendingOrderCounter;
import com.example.monkey.domain.product.ProductCatalog;
import com.example.monkey.domain.security.ApiRateLimiter;
import com.example.monkey.domain.storage.ImageReferenceService;
import com.example.monkey.domain.storage.ImageUsageChecker;
import com.example.monkey.domain.storage.ObjectStorageService;
import com.example.monkey.domain.storage.StoredImageReferenceReader;
import com.example.monkey.domain.storage.UploadFile;
import com.example.monkey.domain.storage.VirusScanner;
import com.example.monkey.domain.user.AddressBook;
import com.example.monkey.domain.user.AuthPrincipal;
import com.example.monkey.domain.user.AuthenticatedPrincipals;
import com.example.monkey.domain.user.HumanVerificationService;
import com.example.monkey.domain.user.LoginAttemptPolicy;
import com.example.monkey.domain.user.PasswordCompromiseChecker;
import com.example.monkey.domain.user.PasswordResetChallengeService;
import com.example.monkey.domain.user.PasswordResetDeliveryService;
import com.example.monkey.domain.user.PiiRetentionStore;
import com.example.monkey.domain.user.SessionTokenService;
import com.example.monkey.domain.user.SessionUser;
import com.example.monkey.domain.user.UserAccountStore;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;

@AnalyzeClasses(packages = "com.example.monkey", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureBoundaryTest {

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_local_security_package = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.security..");

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_repository_package = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_jpa_repositories = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(JpaRepository.class);

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_entity_package = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..entity..");

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_assembler_package = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..assembler..");

    @ArchTest
    static final ArchRule controllers_do_not_use_field_injection = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..controller..")
            .should()
            .beAnnotatedWith(Autowired.class);

    @ArchTest
    static final ArchRule services_do_not_use_field_injection = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..service..")
            .should()
            .beAnnotatedWith(Autowired.class);

    @ArchTest
    static final ArchRule services_do_not_depend_on_infrastructure_package = noClasses()
            .that()
            .resideInAPackage("..service..")
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
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.data.domain..");

    @ArchTest
    static final ArchRule services_do_not_depend_on_spring_multipart_uploads = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.web.multipart..");

    @ArchTest
    static final ArchRule services_do_not_depend_on_servlet_api = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("jakarta.servlet..");

    @ArchTest
    static final ArchRule entities_do_not_depend_on_security_package = noClasses()
            .that()
            .resideInAPackage("..entity..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.security..");

    @ArchTest
    static final ArchRule tasks_do_not_depend_on_repository_package = noClasses()
            .that()
            .resideInAPackage("..task..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule tasks_do_not_depend_on_entity_package = noClasses()
            .that()
            .resideInAPackage("..task..")
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
    static final ArchRule privacy_infrastructure_does_not_depend_on_security_package = noClasses()
            .that()
            .resideInAPackage("..infrastructure.privacy..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.example.monkey.security..");

    @Test
    void authenticationIdentityTypesStayInDomainUser() {
        assertThat(AuthPrincipal.class.getPackageName()).isEqualTo("com.example.monkey.domain.user");
        assertThat(AuthenticatedPrincipals.class.getPackageName()).isEqualTo("com.example.monkey.domain.user");
        assertThat(SessionUser.class.getPackageName()).isEqualTo("com.example.monkey.domain.user");
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
    static final ArchRule order_store_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(OrderStore.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

    @ArchTest
    static final ArchRule product_catalog_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(ProductCatalog.class)
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
    static final ArchRule upload_file_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(UploadFile.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

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
    static final ArchRule session_token_service_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(SessionTokenService.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");

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
    static final ArchRule virus_scanner_adapters_stay_out_of_service_package = classes()
            .that()
            .areAssignableTo(VirusScanner.class)
            .and()
            .areNotInterfaces()
            .should()
            .resideOutsideOfPackage("..service..");
}

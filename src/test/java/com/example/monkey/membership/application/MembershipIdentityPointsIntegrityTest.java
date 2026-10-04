package com.example.monkey.membership.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.membership.application.dto.IdentityReviewRequestDto;
import com.example.monkey.membership.application.dto.LevelChangeRequestDto;
import com.example.monkey.membership.application.dto.MemberProfileDto;
import com.example.monkey.membership.application.dto.PointsEarnRequestDto;
import com.example.monkey.membership.application.dto.PointsRedeemRequestDto;
import com.example.monkey.membership.application.dto.RealNameVerifyRequestDto;
import com.example.monkey.membership.domain.IdentityVerificationStatus;
import com.example.monkey.membership.domain.MemberProfile;
import com.example.monkey.membership.domain.MembershipActivityStore;
import com.example.monkey.membership.domain.MembershipCheckIn;
import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.membership.domain.MembershipLevelTransitionResolver;
import com.example.monkey.membership.domain.MembershipStore;
import com.example.monkey.membership.domain.PointsLedgerEntry;
import com.example.monkey.membership.domain.PointsLedgerType;
import com.example.monkey.membership.domain.PointsWallet;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.domain.UserMfaVerifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MembershipIdentityPointsIntegrityTest {

    private static final SessionUser USER = new SessionUser(7L, "USER");
    private static final SessionUser ADMIN = new SessionUser(99L, "ADMIN");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 4, 10, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-04T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void selfSubmittedIdentityIsNotReportedAsVerifiedOrAuditedAsVerified() {
        MembershipStore store = mock(MembershipStore.class);
        AuditService auditService = mock(AuditService.class);
        when(store.findProfile(7L)).thenReturn(Optional.of(profile()));
        when(store.findWallet(7L)).thenReturn(Optional.of(wallet()));
        when(store.saveProfile(any(MemberProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        stubDashboardReads(store);

        MembershipApplicationService service = service(store, userAccountStore(USER, false), auditService);

        var dashboard = service.verifyIdentity(USER, new RealNameVerifyRequestDto("Alice", "420001199001010010"));

        assertThat(dashboard.profile().verified()).isFalse();
        verify(auditService)
                .record(
                        eq("MEMBERSHIP_IDENTITY_SUBMITTED"),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(7L),
                        eq("USER"),
                        eq("membership:7"),
                        isNull(),
                        contains("status=PENDING"));
        verify(auditService, never())
                .record(
                        eq(AuditService.MEMBERSHIP_IDENTITY_VERIFIED),
                        anyString(),
                        any(),
                        anyString(),
                        anyString(),
                        any(),
                        anyString());
    }

    @Test
    void membershipProfileDtoExposesAnExplicitIdentityStatus() {
        assertThat(Arrays.stream(MemberProfileDto.class.getRecordComponents()).map(component -> component.getName()))
                .contains("identityStatus");
    }

    @Test
    void membershipControllerExposesAnExplicitAdminIdentityReviewAction() throws Exception {
        String controller = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/example/monkey/membership/interfaces/MembershipController.java"));

        assertThat(controller).contains("/admin/{userId}/identity/review").contains("reviewIdentity");
    }

    @Test
    void onlyAnMfaBackedAdministratorCanMarkAPendingIdentityVerified() {
        MembershipStore store = mock(MembershipStore.class);
        AuditService auditService = mock(AuditService.class);
        when(store.findProfile(7L)).thenReturn(Optional.of(submittedProfile()));
        when(store.findWallet(7L)).thenReturn(Optional.of(wallet()));
        when(store.saveProfile(any(MemberProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        stubDashboardReads(store);
        UserMfaVerifier mfaVerifier = mock(UserMfaVerifier.class);
        when(mfaVerifier.verifyCode("SECRET", "123456")).thenReturn(true);

        MembershipApplicationService service = service(store, userAccountStore(ADMIN, true), auditService, mfaVerifier);

        var dashboard = service.reviewIdentity(
                ADMIN,
                7L,
                new IdentityReviewRequestDto(IdentityVerificationStatus.VERIFIED, "matched records", "123456"));

        assertThat(dashboard.profile().identityStatus()).isEqualTo(IdentityVerificationStatus.VERIFIED);
        assertThat(dashboard.profile().verified()).isTrue();
        verify(auditService)
                .record(
                        eq(AuditService.MEMBERSHIP_IDENTITY_VERIFIED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(99L),
                        eq("ADMIN"),
                        eq("membership:7"),
                        isNull(),
                        contains("status=VERIFIED"));
    }

    @Test
    void identityReviewRejectsWithAnExplicitStatusAndLeavesVerifiedAtEmpty() {
        MembershipStore store = mock(MembershipStore.class);
        ArgumentCaptor<MemberProfile> savedProfile = ArgumentCaptor.forClass(MemberProfile.class);
        when(store.findProfile(7L)).thenReturn(Optional.of(submittedProfile()));
        when(store.findWallet(7L)).thenReturn(Optional.of(wallet()));
        when(store.saveProfile(any(MemberProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        stubDashboardReads(store);
        UserMfaVerifier mfaVerifier = mock(UserMfaVerifier.class);
        when(mfaVerifier.verifyCode("SECRET", "123456")).thenReturn(true);

        MembershipApplicationService service =
                service(store, userAccountStore(ADMIN, true), mock(AuditService.class), mfaVerifier);

        var dashboard = service.reviewIdentity(
                ADMIN,
                7L,
                new IdentityReviewRequestDto(IdentityVerificationStatus.REJECTED, "unmatched records", "123456"));

        assertThat(dashboard.profile().identityStatus()).isEqualTo(IdentityVerificationStatus.REJECTED);
        assertThat(dashboard.profile().verified()).isFalse();
        verify(store).saveProfile(savedProfile.capture());
        assertThat(savedProfile.getValue().verifiedAt()).isNull();
    }

    @Test
    void anAdministratorCannotReviewAProfileThatHasNotBeenSubmitted() {
        MembershipStore store = mock(MembershipStore.class);
        when(store.findProfile(7L)).thenReturn(Optional.of(profile()));
        when(store.findWallet(7L)).thenReturn(Optional.of(wallet()));
        when(store.saveProfile(any(MemberProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        stubDashboardReads(store);
        UserMfaVerifier mfaVerifier = mock(UserMfaVerifier.class);
        when(mfaVerifier.verifyCode("SECRET", "123456")).thenReturn(true);

        MembershipApplicationService service =
                service(store, userAccountStore(ADMIN, true), mock(AuditService.class), mfaVerifier);

        assertThatThrownBy(() -> service.reviewIdentity(
                        ADMIN,
                        7L,
                        new IdentityReviewRequestDto(IdentityVerificationStatus.VERIFIED, "no submission", "123456")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.example.monkey.shared.domain.exception.ErrorCode.CONFLICT));
        verify(store, never()).saveProfile(any(MemberProfile.class));
    }

    @Test
    void directUserCallsCannotUseAdminCompatibilityPointsEndpoint() {
        MembershipStore store = mock(MembershipStore.class);
        stubPointMutationReads(store);
        when(store.saveLedger(any(PointsLedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.saveProfile(any(MemberProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MembershipApplicationService service = service(store, userAccountStore(USER, false), mock(AuditService.class));

        assertThatThrownBy(() -> service.earnPoints(
                        USER, new PointsEarnRequestDto(9001L, BigDecimal.valueOf(25), "purchase"), "points-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.example.monkey.shared.domain.exception.ErrorCode.FORBIDDEN));
    }

    @Test
    void directUserCallsCannotUseAdminCompatibilityLevelEndpoint() {
        MembershipStore store = mock(MembershipStore.class);
        when(store.findProfile(7L)).thenReturn(Optional.of(profile()));
        when(store.findWallet(7L)).thenReturn(Optional.of(wallet()));
        stubDashboardReads(store);
        UserMfaVerifier mfaVerifier = mock(UserMfaVerifier.class);
        when(mfaVerifier.verifyCode("SECRET", "123456")).thenReturn(true);

        MembershipApplicationService service =
                service(store, userAccountStore(USER, true), mock(AuditService.class), mfaVerifier);

        assertThatThrownBy(() -> service.changeLevel(
                        USER, new LevelChangeRequestDto(MembershipLevel.SILVER, "manual", "123456")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.example.monkey.shared.domain.exception.ErrorCode.FORBIDDEN));
    }

    @Test
    void changedPurchaseReplayIntentConflictsInsteadOfReturningTheOldLedger() {
        MembershipStore store = mock(MembershipStore.class);
        PointsLedgerEntry existing = new PointsLedgerEntry(
                1001L,
                99L,
                PointsLedgerType.PURCHASE,
                120,
                BigDecimal.valueOf(120, 2),
                9001L,
                "order:9001",
                "purchase-1",
                NOW);
        stubPointMutationReads(store);
        when(store.findLedger(99L, "purchase-1")).thenReturn(Optional.empty(), Optional.of(existing));
        when(store.saveLedger(any(PointsLedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.saveProfile(any(MemberProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MembershipApplicationService service = service(store, userAccountStore(ADMIN, false), mock(AuditService.class));
        service.earnPoints(ADMIN, new PointsEarnRequestDto(9001L, BigDecimal.valueOf(120), "order:9001"), "purchase-1");

        assertThatThrownBy(() -> service.earnPoints(
                        ADMIN, new PointsEarnRequestDto(9002L, BigDecimal.valueOf(121), "order:9002"), "purchase-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.example.monkey.shared.domain.exception.ErrorCode.CONFLICT));
    }

    @Test
    void exactPurchaseReplayReturnsTheOriginalLedgerResult() {
        MembershipStore store = mock(MembershipStore.class);
        PointsLedgerEntry existing = new PointsLedgerEntry(
                1001L,
                99L,
                PointsLedgerType.PURCHASE,
                120,
                BigDecimal.valueOf(120, 2),
                9001L,
                "order:9001",
                "purchase-1",
                NOW);
        stubPointMutationReads(store);
        when(store.findLedger(99L, "purchase-1")).thenReturn(Optional.empty(), Optional.of(existing));
        when(store.saveLedger(any(PointsLedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.saveProfile(any(MemberProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MembershipApplicationService service = service(store, userAccountStore(ADMIN, false), mock(AuditService.class));
        var first = service.earnPoints(
                ADMIN, new PointsEarnRequestDto(9001L, BigDecimal.valueOf(120), "order:9001"), "purchase-1");
        var replay = service.earnPoints(
                ADMIN, new PointsEarnRequestDto(9001L, BigDecimal.valueOf(120), "order:9001"), "purchase-1");

        assertThat(replay).isEqualTo(first);
        verify(store).saveLedger(any(PointsLedgerEntry.class));
    }

    @Test
    void changedRedeemReplayIntentConflictsInsteadOfReturningTheOldLedger() {
        MembershipStore store = mock(MembershipStore.class);
        PointsLedgerEntry existing = ledger(-50, PointsLedgerType.REDEEM, null, "wallet-redemption", "redeem-1");
        when(store.findProfile(7L)).thenReturn(Optional.of(profile()));
        when(store.findWallet(7L)).thenReturn(Optional.of(wallet(100)));
        when(store.findLedger(7L, "redeem-1")).thenReturn(Optional.empty(), Optional.of(existing));
        when(store.updateWallet(any(PointsWallet.class))).thenReturn(true);
        when(store.saveLedger(any(PointsLedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MembershipApplicationService service = service(store, userAccountStore(USER, false), mock(AuditService.class));
        service.redeemPoints(USER, new PointsRedeemRequestDto(50, "wallet-redemption"), "redeem-1");

        assertThatThrownBy(() ->
                        service.redeemPoints(USER, new PointsRedeemRequestDto(60, "wallet-redemption"), "redeem-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.example.monkey.shared.domain.exception.ErrorCode.CONFLICT));
    }

    @Test
    void changedAdminAdjustmentReplayIntentConflictsInsteadOfReturningTheOldLedger() {
        MembershipStore store = mock(MembershipStore.class);
        PointsLedgerEntry existing = ledger(25, PointsLedgerType.ADJUST, null, "manual correction", "adjust-1");
        when(store.findProfile(7L)).thenReturn(Optional.of(profile()));
        when(store.findWallet(7L)).thenReturn(Optional.of(wallet()));
        when(store.findLedger(7L, "adjust-1")).thenReturn(Optional.empty(), Optional.of(existing));
        when(store.updateWallet(any(PointsWallet.class))).thenReturn(true);
        when(store.saveLedger(any(PointsLedgerEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(store.saveProfile(any(MemberProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MembershipApplicationService service = service(store, userAccountStore(ADMIN, false), mock(AuditService.class));
        service.earnPointsAsAdmin(
                ADMIN, 7L, new PointsEarnRequestDto(null, BigDecimal.valueOf(25), "manual correction"), "adjust-1");

        assertThatThrownBy(() -> service.earnPointsAsAdmin(
                        ADMIN,
                        7L,
                        new PointsEarnRequestDto(null, BigDecimal.valueOf(26), "different correction"),
                        "adjust-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.example.monkey.shared.domain.exception.ErrorCode.CONFLICT));
    }

    @Test
    void checkInReplayWithTheSameKeyOnAnotherDateConflicts() {
        MembershipStore store = mock(MembershipStore.class);
        MembershipCheckIn existing =
                new MembershipCheckIn(4001L, 7L, LocalDate.of(2026, 7, 3), 2, 12, "check-in-1", NOW.minusDays(1));
        when(store.findCheckInByIdempotencyKey(7L, "check-in-1")).thenReturn(Optional.of(existing));
        when(store.findWallet(7L)).thenReturn(Optional.of(wallet(12)));

        MembershipApplicationService service = service(store, userAccountStore(USER, false), mock(AuditService.class));

        assertThatThrownBy(() -> service.checkIn(USER, "check-in-1"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.example.monkey.shared.domain.exception.ErrorCode.CONFLICT));
    }

    @Test
    void checkInWithANewKeyAfterTodayWasAlreadyCompletedConflicts() {
        MembershipStore store = mock(MembershipStore.class);
        MembershipCheckIn existing =
                new MembershipCheckIn(4001L, 7L, LocalDate.of(2026, 7, 4), 2, 12, "check-in-1", NOW);
        when(store.findCheckInByIdempotencyKey(7L, "check-in-2")).thenReturn(Optional.empty());
        when(store.findCheckIn(7L, LocalDate.of(2026, 7, 4))).thenReturn(Optional.of(existing));

        MembershipApplicationService service = service(store, userAccountStore(USER, false), mock(AuditService.class));

        assertThatThrownBy(() -> service.checkIn(USER, "check-in-2"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(com.example.monkey.shared.domain.exception.ErrorCode.CONFLICT));
    }

    @Test
    void pointsLedgerCarriesPersistentMutationFingerprint() {
        assertThat(Arrays.stream(PointsLedgerEntry.class.getRecordComponents()).map(component -> component.getName()))
                .contains("mutationFingerprint");
    }

    private static MembershipApplicationService service(
            MembershipStore store, UserAccountStore accountStore, AuditService auditService) {
        return service(store, accountStore, auditService, mock(UserMfaVerifier.class));
    }

    private static MembershipApplicationService service(
            MembershipStore store,
            UserAccountStore accountStore,
            AuditService auditService,
            UserMfaVerifier mfaVerifier) {
        MembershipActivityStore activityStore = mock(MembershipActivityStore.class);
        when(activityStore.findRecent(any(), anyInt())).thenReturn(List.of());
        return new MembershipApplicationService(
                store,
                activityStore,
                mock(MembershipLevelTransitionResolver.class),
                accountStore,
                mfaVerifier,
                () -> 1001L,
                auditService,
                CLOCK,
                Duration.ofDays(7));
    }

    private static UserAccountStore userAccountStore(SessionUser principal, boolean mfaEnabled) {
        UserAccountStore store = mock(UserAccountStore.class);
        when(store.findById(principal.id())).thenReturn(Optional.of(account(principal, mfaEnabled)));
        when(store.findById(7L)).thenReturn(Optional.of(account(USER, false)));
        return store;
    }

    private static UserAccount account(SessionUser principal, boolean mfaEnabled) {
        List<String> authorities =
                "ADMIN".equals(principal.role()) ? List.of("MEMBERSHIP_ADMIN") : List.of("MEMBERSHIP_WRITE");
        return new UserAccount(
                principal.id(),
                "user-" + principal.id(),
                "hash",
                "18800000000",
                "user@example.com",
                null,
                principal.role(),
                "User " + principal.id(),
                null,
                false,
                "SECRET",
                mfaEnabled,
                authorities);
    }

    private static MemberProfile profile() {
        return new MemberProfile(401L, 7L, MembershipLevel.BASIC, 0, null, null, null, null, null, 0, NOW, NOW);
    }

    private static MemberProfile submittedProfile() {
        return profile().submitIdentity("Alice", "alice-hmac", "420001199001010010", "id-hmac", NOW);
    }

    private static PointsWallet wallet() {
        return wallet(0);
    }

    private static PointsWallet wallet(long balance) {
        return new PointsWallet(501L, 7L, balance, balance, 0, 0, NOW, NOW);
    }

    private static PointsLedgerEntry ledger(
            long points, PointsLedgerType type, Long orderId, String referenceKey, String idempotencyKey) {
        return new PointsLedgerEntry(
                6001L,
                7L,
                type,
                points,
                BigDecimal.valueOf(Math.abs(points), 2),
                orderId,
                referenceKey,
                idempotencyKey,
                NOW);
    }

    private static void stubDashboardReads(MembershipStore store) {
        when(store.findCouponWallet(7L)).thenReturn(List.of());
        when(store.findCollections(7L)).thenReturn(List.of());
    }

    private static void stubPointMutationReads(MembershipStore store) {
        when(store.findProfile(any())).thenReturn(Optional.of(profile()));
        when(store.findWallet(any())).thenReturn(Optional.of(wallet()));
        when(store.updateWallet(any(PointsWallet.class))).thenReturn(true);
    }
}

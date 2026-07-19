package com.example.monkey.membership.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.membership.application.dto.LevelChangeRequestDto;
import com.example.monkey.membership.application.dto.PointsEarnRequestDto;
import com.example.monkey.membership.domain.MemberProfile;
import com.example.monkey.membership.domain.MembershipActivityStore;
import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.membership.domain.MembershipLevelTransitionResolver;
import com.example.monkey.membership.domain.MembershipStore;
import com.example.monkey.membership.domain.PointsLedgerEntry;
import com.example.monkey.membership.domain.PointsWallet;
import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.id.IdGenerator;
import com.example.monkey.user.domain.UserAccountStore;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;
import com.example.monkey.user.domain.UserMfaVerifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MembershipAdminApplicationServiceTest {

    private static final SessionUser ADMIN = new SessionUser(99L, "ADMIN");
    private static final Long MEMBER_ID = 7L;
    private static final String VALID_TOTP_CODE = "123" + "456";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 4, 10, 0);

    private final MembershipStore membershipStore = mock(MembershipStore.class);
    private final MembershipActivityStore activityStore = mock(MembershipActivityStore.class);
    private final MembershipLevelTransitionResolver transitionResolver = mock(MembershipLevelTransitionResolver.class);
    private final UserAccountStore userAccountStore = mock(UserAccountStore.class);
    private final UserMfaVerifier mfaVerifier = mock(UserMfaVerifier.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final AuditService auditService = mock(AuditService.class);
    private final MembershipApplicationService service = new MembershipApplicationService(
            membershipStore,
            activityStore,
            transitionResolver,
            userAccountStore,
            mfaVerifier,
            idGenerator,
            auditService,
            Clock.fixed(Instant.parse("2026-07-04T02:00:00Z"), ZoneId.of("Asia/Shanghai")),
            Duration.ofDays(7));

    @BeforeEach
    void setUp() {
        when(userAccountStore.findById(99L)).thenReturn(Optional.of(account(99L, "ADMIN")));
        when(userAccountStore.findById(MEMBER_ID)).thenReturn(Optional.of(account(MEMBER_ID, "USER")));
        when(membershipStore.findCouponWallet(MEMBER_ID)).thenReturn(List.of());
        when(membershipStore.findCollections(MEMBER_ID)).thenReturn(List.of());
        when(activityStore.findRecent(MEMBER_ID, 20)).thenReturn(List.of());
        when(idGenerator.nextId()).thenReturn(1001L, 1002L, 1003L);
    }

    @Test
    void pointsAdjustmentTargetsMemberRequiresReasonAndAuditsOperator() {
        MemberProfile basic = profile(MembershipLevel.BASIC);
        when(membershipStore.findProfile(MEMBER_ID)).thenReturn(Optional.of(basic));
        when(membershipStore.findWallet(MEMBER_ID))
                .thenReturn(Optional.of(new PointsWallet(501L, MEMBER_ID, 0, 0, 0, 0, NOW, NOW)));
        when(membershipStore.findLedger(MEMBER_ID, "adjust-1")).thenReturn(Optional.empty());
        when(membershipStore.updateWallet(any(PointsWallet.class))).thenReturn(true);
        when(membershipStore.saveLedger(any(PointsLedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(membershipStore.saveProfile(any(MemberProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.earnPointsAsAdmin(
                        ADMIN, MEMBER_ID, new PointsEarnRequestDto(null, BigDecimal.valueOf(25), " "), "adjust-1"))
                .isInstanceOf(BusinessException.class);

        var adjusted = service.earnPointsAsAdmin(
                ADMIN,
                MEMBER_ID,
                new PointsEarnRequestDto(null, BigDecimal.valueOf(25), "Service recovery"),
                "adjust-1");

        assertThat(adjusted.points()).isEqualTo(25);
        verify(membershipStore).findWallet(MEMBER_ID);
        verify(auditService)
                .record(
                        eq(AuditService.MEMBERSHIP_POINTS_EARNED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(99L),
                        eq("ADMIN"),
                        startsWith("points:"),
                        isNull(),
                        contains("targetUserId=7"));
    }

    @Test
    void levelChangeTargetsMemberRequiresReasonAndRecordsOperator() {
        MemberProfile basic = profile(MembershipLevel.BASIC);
        MemberProfile silver = profile(MembershipLevel.SILVER);
        when(membershipStore.findProfile(MEMBER_ID)).thenReturn(Optional.of(basic), Optional.of(silver));
        when(membershipStore.findWallet(MEMBER_ID))
                .thenReturn(Optional.of(new PointsWallet(501L, MEMBER_ID, 0, 0, 0, 0, NOW, NOW)));
        when(membershipStore.updateLevel(MEMBER_ID, basic.version(), MembershipLevel.SILVER, NOW))
                .thenReturn(true);
        when(mfaVerifier.verifyCode("SECRET", VALID_TOTP_CODE)).thenReturn(true);

        assertThatThrownBy(() -> service.changeLevelAsAdmin(
                        ADMIN, MEMBER_ID, new LevelChangeRequestDto(MembershipLevel.SILVER, " ", VALID_TOTP_CODE)))
                .isInstanceOf(BusinessException.class);

        var dashboard = service.changeLevelAsAdmin(
                ADMIN,
                MEMBER_ID,
                new LevelChangeRequestDto(MembershipLevel.SILVER, "Retention review", VALID_TOTP_CODE));

        assertThat(dashboard.profile().userId()).isEqualTo(MEMBER_ID);
        assertThat(dashboard.profile().level()).isEqualTo(MembershipLevel.SILVER);
        verify(membershipStore)
                .saveLevelHistory(
                        any(),
                        eq(MEMBER_ID),
                        eq(MembershipLevel.BASIC),
                        eq(MembershipLevel.SILVER),
                        eq("Retention review"),
                        eq(99L),
                        eq(NOW));
        verify(auditService)
                .record(
                        eq(AuditService.MEMBERSHIP_LEVEL_CHANGED),
                        eq(AuditService.OUTCOME_SUCCESS),
                        eq(99L),
                        eq("ADMIN"),
                        eq("membership:7"),
                        isNull(),
                        contains("reason=Retention review"));
    }

    private static MemberProfile profile(MembershipLevel level) {
        return new MemberProfile(401L, MEMBER_ID, level, 0, null, null, null, null, null, 0, NOW, NOW);
    }

    private static UserAccount account(Long id, String role) {
        return new UserAccount(
                id,
                "user-" + id,
                "hash",
                "18800000000",
                "user@example.com",
                null,
                role,
                "User " + id,
                null,
                false,
                "SECRET",
                true,
                List.of("MEMBERSHIP_ADMIN"));
    }
}

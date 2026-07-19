package com.example.monkey.membership.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.membership.application.MembershipApplicationService;
import com.example.monkey.membership.application.dto.LevelChangeRequestDto;
import com.example.monkey.membership.application.dto.MembershipDashboardDto;
import com.example.monkey.membership.application.dto.PointsEarnRequestDto;
import com.example.monkey.membership.application.dto.PointsLedgerEntryDto;
import com.example.monkey.membership.domain.MembershipLevel;
import com.example.monkey.shared.application.security.SessionUser;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

class MembershipControllerAdminTest {

    private static final SessionUser ADMIN = new SessionUser(99L, "ADMIN");
    private static final Long MEMBER_ID = 7L;

    private final MembershipApplicationService service = mock(MembershipApplicationService.class);
    private final MembershipController controller = new MembershipController(service);

    @Test
    void forwardsTargetMemberToAdminApplicationMethods() {
        MembershipDashboardDto dashboard = mock(MembershipDashboardDto.class);
        PointsLedgerEntryDto ledger = mock(PointsLedgerEntryDto.class);
        PointsEarnRequestDto pointsRequest = new PointsEarnRequestDto(null, BigDecimal.valueOf(25), "Service recovery");
        LevelChangeRequestDto levelRequest =
                new LevelChangeRequestDto(MembershipLevel.SILVER, "Retention review", "123456");
        when(service.dashboardAsAdmin(ADMIN, MEMBER_ID)).thenReturn(dashboard);
        when(service.earnPointsAsAdmin(ADMIN, MEMBER_ID, pointsRequest, "adjust-1"))
                .thenReturn(ledger);
        when(service.changeLevelAsAdmin(ADMIN, MEMBER_ID, levelRequest)).thenReturn(dashboard);

        assertThat(controller.adminDashboard(MEMBER_ID, ADMIN).data()).isSameAs(dashboard);
        assertThat(controller
                        .adminEarnPoints(MEMBER_ID, "adjust-1", pointsRequest, ADMIN)
                        .data())
                .isSameAs(ledger);
        assertThat(controller.adminChangeLevel(MEMBER_ID, levelRequest, ADMIN).data())
                .isSameAs(dashboard);

        verify(service).dashboardAsAdmin(ADMIN, MEMBER_ID);
        verify(service).earnPointsAsAdmin(ADMIN, MEMBER_ID, pointsRequest, "adjust-1");
        verify(service).changeLevelAsAdmin(ADMIN, MEMBER_ID, levelRequest);
    }

    @Test
    void exposesOnlyGuardedAdminRoutesForTargetMemberOperations() throws Exception {
        Method dashboard = MembershipController.class.getMethod("adminDashboard", Long.class, SessionUser.class);
        Method points = MembershipController.class.getMethod(
                "adminEarnPoints", Long.class, String.class, PointsEarnRequestDto.class, SessionUser.class);
        Method level = MembershipController.class.getMethod(
                "adminChangeLevel", Long.class, LevelChangeRequestDto.class, SessionUser.class);

        assertThat(dashboard.getAnnotation(GetMapping.class).value()).containsExactly("/admin/{userId}/dashboard");
        assertThat(points.getAnnotation(PostMapping.class).value()).containsExactly("/admin/{userId}/points/earn");
        assertThat(level.getAnnotation(PostMapping.class).value()).containsExactly("/admin/{userId}/level");
        assertThat(dashboard.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('MEMBERSHIP_ADMIN')");
        assertThat(points.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('MEMBERSHIP_ADMIN')");
        assertThat(level.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('MEMBERSHIP_ADMIN')");
    }
}

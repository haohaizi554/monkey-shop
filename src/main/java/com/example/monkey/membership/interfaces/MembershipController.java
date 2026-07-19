package com.example.monkey.membership.interfaces;

import com.example.monkey.membership.application.MembershipApplicationService;
import com.example.monkey.membership.application.dto.BrowseRecordRequestDto;
import com.example.monkey.membership.application.dto.CheckInResponseDto;
import com.example.monkey.membership.application.dto.CollectionRequestDto;
import com.example.monkey.membership.application.dto.LevelChangeRequestDto;
import com.example.monkey.membership.application.dto.MemberCollectionDto;
import com.example.monkey.membership.application.dto.MembershipDashboardDto;
import com.example.monkey.membership.application.dto.PointsEarnRequestDto;
import com.example.monkey.membership.application.dto.PointsLedgerEntryDto;
import com.example.monkey.membership.application.dto.PointsRedeemRequestDto;
import com.example.monkey.membership.application.dto.PriceDropScanResponseDto;
import com.example.monkey.membership.application.dto.RealNameVerifyRequestDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/membership", "/api/v1/membership"})
public class MembershipController {

    private final MembershipApplicationService membershipApplicationService;

    public MembershipController(MembershipApplicationService membershipApplicationService) {
        this.membershipApplicationService = membershipApplicationService;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('MEMBERSHIP_READ')")
    public Result<MembershipDashboardDto> dashboard(@AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(membershipApplicationService.dashboard(currentUser));
    }

    @GetMapping("/admin/{userId}/dashboard")
    @PreAuthorize("hasAuthority('MEMBERSHIP_ADMIN')")
    public Result<MembershipDashboardDto> adminDashboard(
            @PathVariable Long userId, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(membershipApplicationService.dashboardAsAdmin(currentUser, userId));
    }

    @PostMapping("/identity")
    @PreAuthorize("hasAuthority('MEMBERSHIP_WRITE')")
    public Result<MembershipDashboardDto> verifyIdentity(
            @Valid @RequestBody RealNameVerifyRequestDto request, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(membershipApplicationService.verifyIdentity(currentUser, request));
    }

    @PostMapping("/check-in")
    @PreAuthorize("hasAuthority('MEMBERSHIP_WRITE')")
    public Result<CheckInResponseDto> checkIn(
            @RequestHeader(value = "Idempotency-Key") @NotBlank String idempotencyKey,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(membershipApplicationService.checkIn(currentUser, idempotencyKey));
    }

    @PostMapping("/points/earn")
    @PreAuthorize("hasAuthority('MEMBERSHIP_ADMIN')")
    public Result<PointsLedgerEntryDto> earnPoints(
            @RequestHeader(value = "Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PointsEarnRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(membershipApplicationService.earnPoints(currentUser, request, idempotencyKey));
    }

    @PostMapping("/admin/{userId}/points/earn")
    @PreAuthorize("hasAuthority('MEMBERSHIP_ADMIN')")
    public Result<PointsLedgerEntryDto> adminEarnPoints(
            @PathVariable Long userId,
            @RequestHeader(value = "Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PointsEarnRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(
                membershipApplicationService.earnPointsAsAdmin(currentUser, userId, request, idempotencyKey));
    }

    @PostMapping("/points/redeem")
    @PreAuthorize("hasAuthority('MEMBERSHIP_WRITE')")
    public Result<PointsLedgerEntryDto> redeemPoints(
            @RequestHeader(value = "Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody PointsRedeemRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(membershipApplicationService.redeemPoints(currentUser, request, idempotencyKey));
    }

    @PostMapping("/level")
    @PreAuthorize("hasAuthority('MEMBERSHIP_ADMIN')")
    public Result<MembershipDashboardDto> changeLevel(
            @Valid @RequestBody LevelChangeRequestDto request, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(membershipApplicationService.changeLevel(currentUser, request));
    }

    @PostMapping("/admin/{userId}/level")
    @PreAuthorize("hasAuthority('MEMBERSHIP_ADMIN')")
    public Result<MembershipDashboardDto> adminChangeLevel(
            @PathVariable Long userId,
            @Valid @RequestBody LevelChangeRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(membershipApplicationService.changeLevelAsAdmin(currentUser, userId, request));
    }

    @PostMapping("/collections")
    @PreAuthorize("hasAuthority('MEMBERSHIP_WRITE')")
    public Result<MemberCollectionDto> addCollection(
            @Valid @RequestBody CollectionRequestDto request, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(membershipApplicationService.addCollection(currentUser, request));
    }

    @DeleteMapping("/collections/{productId}")
    @PreAuthorize("hasAuthority('MEMBERSHIP_WRITE')")
    public Result<Void> removeCollection(
            @PathVariable Long productId, @AuthenticationPrincipal SessionUser currentUser) {
        membershipApplicationService.removeCollection(currentUser, productId);
        return Result.success();
    }

    @PostMapping("/browse")
    @PreAuthorize("hasAuthority('MEMBERSHIP_WRITE')")
    public Result<Void> recordBrowse(
            @Valid @RequestBody BrowseRecordRequestDto request, @AuthenticationPrincipal SessionUser currentUser) {
        membershipApplicationService.recordBrowse(currentUser, request);
        return Result.success();
    }

    @PostMapping("/price-drops/scan")
    @PreAuthorize("hasAuthority('MEMBERSHIP_ADMIN')")
    public Result<PriceDropScanResponseDto> scanPriceDrops() {
        return Result.success(membershipApplicationService.scanPriceDrops());
    }
}

package com.example.monkey.tracking.interfaces;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.web.ClientIps;
import com.example.monkey.tracking.application.TrackingApplicationService;
import com.example.monkey.tracking.application.dto.FunnelStepDto;
import com.example.monkey.tracking.application.dto.ProductProfileDto;
import com.example.monkey.tracking.application.dto.RealtimeDashboardDto;
import com.example.monkey.tracking.application.dto.TrackingEventRequestDto;
import com.example.monkey.tracking.application.dto.TrackingEventResponseDto;
import com.example.monkey.tracking.application.dto.TrackingWindowRequestDto;
import com.example.monkey.tracking.application.dto.UserProfileTagDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/tracking", "/api/v1/tracking"})
public class TrackingController {

    private final TrackingApplicationService trackingApplicationService;

    public TrackingController(TrackingApplicationService trackingApplicationService) {
        this.trackingApplicationService = trackingApplicationService;
    }

    @PostMapping("/events")
    @PreAuthorize("permitAll()")
    public Result<TrackingEventResponseDto> recordEvent(
            @Valid @RequestBody TrackingEventRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser,
            HttpServletRequest httpRequest) {
        return Result.success(
                trackingApplicationService.recordEvent(currentUser, request, ClientIps.resolve(httpRequest)));
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('TRACKING_ADMIN')")
    public Result<RealtimeDashboardDto> dashboard(@Valid @ModelAttribute TrackingWindowRequestDto request) {
        return Result.success(trackingApplicationService.dashboard(request.minutes()));
    }

    @GetMapping("/funnel")
    @PreAuthorize("hasAuthority('TRACKING_ADMIN')")
    public Result<List<FunnelStepDto>> funnel(@Valid @ModelAttribute TrackingWindowRequestDto request) {
        return Result.success(trackingApplicationService.funnel(request.minutes()));
    }

    @GetMapping("/profile/me")
    @PreAuthorize("hasAuthority('TRACKING_READ')")
    public Result<UserProfileTagDto> currentUserProfile(@AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(trackingApplicationService.currentUserProfile(currentUser));
    }

    @GetMapping("/profile/{userId}")
    @PreAuthorize("hasAuthority('TRACKING_ADMIN')")
    public Result<UserProfileTagDto> userProfile(@PathVariable Long userId) {
        return Result.success(trackingApplicationService.userProfile(userId));
    }

    @GetMapping("/products/{productId}")
    @PreAuthorize("hasAuthority('TRACKING_ADMIN')")
    public Result<ProductProfileDto> productProfile(@PathVariable Long productId) {
        return Result.success(trackingApplicationService.productProfile(productId));
    }
}

package com.example.monkey.logistics.interfaces;

import com.example.monkey.logistics.application.LogisticsApplicationService;
import com.example.monkey.logistics.application.dto.AddressParseRequestDto;
import com.example.monkey.logistics.application.dto.FreightQuoteRequestDto;
import com.example.monkey.logistics.application.dto.FreightQuoteResponseDto;
import com.example.monkey.logistics.application.dto.LogisticsTrackingResponseDto;
import com.example.monkey.logistics.application.dto.ParsedAddressDto;
import com.example.monkey.logistics.application.dto.ShipmentCreateRequestDto;
import com.example.monkey.logistics.application.dto.TrackingWebhookRequestDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.web.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/logistics", "/api/v1/logistics"})
public class LogisticsController {

    private final LogisticsApplicationService logisticsApplicationService;

    public LogisticsController(LogisticsApplicationService logisticsApplicationService) {
        this.logisticsApplicationService = logisticsApplicationService;
    }

    @PostMapping("/shipments")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN')")
    public Result<LogisticsTrackingResponseDto> createShipment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ShipmentCreateRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(logisticsApplicationService.createShipment(currentUser, request, idempotencyKey));
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN')")
    public Result<LogisticsTrackingResponseDto> findByOrder(
            @PathVariable Long orderId, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(logisticsApplicationService.findByOrder(currentUser, orderId));
    }

    @GetMapping("/tracking/{trackingNo}")
    @PreAuthorize("hasAuthority('ORDER_READ_OWN')")
    public Result<LogisticsTrackingResponseDto> findByTrackingNo(
            @PathVariable String trackingNo, @AuthenticationPrincipal SessionUser currentUser) {
        return Result.success(logisticsApplicationService.findByTrackingNo(currentUser, trackingNo));
    }

    @PostMapping("/freight/quote")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<FreightQuoteResponseDto> quoteFreight(@Valid @RequestBody FreightQuoteRequestDto request) {
        return Result.success(logisticsApplicationService.quoteFreight(request));
    }

    @PostMapping("/address/parse")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public Result<ParsedAddressDto> parseAddress(@Valid @RequestBody AddressParseRequestDto request) {
        return Result.success(logisticsApplicationService.parseAddress(request.text()));
    }

    @PostMapping("/webhook")
    public Result<LogisticsTrackingResponseDto> webhook(
            @Valid @RequestBody TrackingWebhookRequestDto request, HttpServletRequest httpRequest) {
        return Result.success(logisticsApplicationService.handleWebhook(request, ClientIps.resolve(httpRequest)));
    }
}

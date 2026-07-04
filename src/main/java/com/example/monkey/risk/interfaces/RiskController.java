package com.example.monkey.risk.interfaces;

import com.example.monkey.risk.application.RiskApplicationService;
import com.example.monkey.risk.application.dto.RiskAssessmentRequestDto;
import com.example.monkey.risk.application.dto.RiskAssessmentResponseDto;
import com.example.monkey.risk.application.dto.RiskReviewCaseDto;
import com.example.monkey.risk.application.dto.RiskReviewResolveRequestDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.web.ClientIps;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/risk", "/api/v1/risk"})
public class RiskController {

    private final RiskApplicationService riskApplicationService;

    public RiskController(RiskApplicationService riskApplicationService) {
        this.riskApplicationService = riskApplicationService;
    }

    @PostMapping("/assess")
    @PreAuthorize("hasAuthority('RISK_WRITE')")
    public Result<RiskAssessmentResponseDto> assess(
            @Valid @RequestBody RiskAssessmentRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser,
            HttpServletRequest httpRequest) {
        return Result.success(riskApplicationService.assess(currentUser, request, ClientIps.resolve(httpRequest)));
    }

    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority('RISK_REVIEW')")
    public Result<List<RiskReviewCaseDto>> reviewQueue() {
        return Result.success(riskApplicationService.reviewQueue());
    }

    @PostMapping("/reviews/{caseId}/resolve")
    @PreAuthorize("hasAuthority('RISK_REVIEW')")
    public Result<RiskReviewCaseDto> resolveReview(
            @PathVariable Long caseId,
            @Valid @RequestBody RiskReviewResolveRequestDto request,
            @AuthenticationPrincipal SessionUser currentUser,
            HttpServletRequest httpRequest) {
        return Result.success(
                riskApplicationService.resolveReview(currentUser, caseId, request, ClientIps.resolve(httpRequest)));
    }
}

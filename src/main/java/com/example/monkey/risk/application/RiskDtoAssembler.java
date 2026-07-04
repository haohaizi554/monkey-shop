package com.example.monkey.risk.application;

import com.example.monkey.risk.application.dto.RiskAssessmentResponseDto;
import com.example.monkey.risk.application.dto.RiskReviewCaseDto;
import com.example.monkey.risk.application.dto.RiskSignalDto;
import com.example.monkey.risk.domain.RiskReviewCase;
import com.example.monkey.risk.domain.RiskScore;
import com.example.monkey.risk.domain.RiskSignal;
import java.util.List;

final class RiskDtoAssembler {

    private RiskDtoAssembler() {}

    static RiskAssessmentResponseDto toAssessment(
            RiskScore score, Long reviewCaseId, boolean productAutoUnlisted, boolean userTokensRevoked) {
        return new RiskAssessmentResponseDto(
                score.userId(),
                score.score(),
                score.decision(),
                signals(score.signals()),
                reviewCaseId,
                productAutoUnlisted,
                userTokensRevoked,
                score.assessedAt());
    }

    static RiskReviewCaseDto toReviewCase(RiskReviewCase reviewCase) {
        return new RiskReviewCaseDto(
                reviewCase.id(),
                reviewCase.userId(),
                reviewCase.orderId(),
                reviewCase.productId(),
                reviewCase.type(),
                reviewCase.score(),
                reviewCase.status(),
                reviewCase.detail(),
                reviewCase.createdAt(),
                reviewCase.handledAt(),
                reviewCase.handlerUserId(),
                reviewCase.resolution());
    }

    private static List<RiskSignalDto> signals(List<RiskSignal> signals) {
        return signals.stream()
                .map(signal -> new RiskSignalDto(signal.type(), signal.weight(), signal.detail()))
                .toList();
    }
}

package com.example.monkey.risk.infrastructure;

import com.example.monkey.risk.domain.RiskReviewStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskReviewCaseRepository extends JpaRepository<RiskReviewCaseEntity, Long> {

    List<RiskReviewCaseEntity> findByStatusOrderByCreatedAtAsc(RiskReviewStatus status, Pageable pageable);
}

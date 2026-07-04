package com.example.monkey.risk.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskScoreRepository extends JpaRepository<RiskScoreEntity, Long> {

    Optional<RiskScoreEntity> findFirstByUserIdOrderByAssessedAtDesc(Long userId);
}

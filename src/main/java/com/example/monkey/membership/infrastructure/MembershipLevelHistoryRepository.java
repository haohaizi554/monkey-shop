package com.example.monkey.membership.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipLevelHistoryRepository extends JpaRepository<MembershipLevelHistoryEntity, Long> {}

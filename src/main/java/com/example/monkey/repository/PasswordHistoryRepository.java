package com.example.monkey.repository;

import com.example.monkey.entity.PasswordHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {

    List<PasswordHistory> findTop5ByUserIdOrderByCreatedAtDesc(Long userId);
}

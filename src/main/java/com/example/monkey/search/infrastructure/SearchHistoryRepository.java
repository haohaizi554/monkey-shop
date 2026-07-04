package com.example.monkey.search.infrastructure;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchHistoryRepository extends JpaRepository<SearchHistoryEntity, Long> {

    List<SearchHistoryEntity> findByUserIdAndKeywordIsNotNullOrderByCreatedAtDesc(Long userId, Pageable pageable);
}

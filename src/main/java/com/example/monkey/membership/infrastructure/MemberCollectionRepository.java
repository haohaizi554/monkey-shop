package com.example.monkey.membership.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberCollectionRepository extends JpaRepository<MemberCollectionEntity, Long> {

    Optional<MemberCollectionEntity> findByUserIdAndProductId(Long userId, Long productId);

    List<MemberCollectionEntity> findByUserIdOrderByCreateTimeDesc(Long userId);

    void deleteByUserIdAndProductId(Long userId, Long productId);

    List<MemberCollectionEntity> findByPriceDropNotifiedFalseAndTargetPriceIsNotNullOrderByUpdateTimeAsc(
            Pageable pageable);
}

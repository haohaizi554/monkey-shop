package com.example.monkey.membership.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MemberCollectionRepository extends JpaRepository<MemberCollectionEntity, Long> {

    Optional<MemberCollectionEntity> findByUserIdAndProductId(Long userId, Long productId);

    List<MemberCollectionEntity> findByUserIdOrderByCreateTimeDesc(Long userId);

    void deleteByUserIdAndProductId(Long userId, Long productId);

    long countByProductImage(String productImage);

    long countByProductImageStartingWith(String productImagePrefix);

    @Query(
            "SELECT collection.productImage FROM MemberCollectionEntity collection "
                    + "WHERE collection.productImage IS NOT NULL ORDER BY collection.id")
    List<String> findProductImages(Pageable pageable);

    List<MemberCollectionEntity> findByPriceDropNotifiedFalseAndTargetPriceIsNotNullOrderByUpdateTimeAsc(
            Pageable pageable);
}

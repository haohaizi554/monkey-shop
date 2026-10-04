package com.example.monkey.product.infrastructure;

import com.example.monkey.product.domain.ProductStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductSpuRepository extends JpaRepository<ProductSpu, Long> {

    List<ProductSpu> findByStatusOrderByIdDesc(ProductStatus status, Pageable pageable);

    @Query("""
            SELECT spu
            FROM ProductSpu spu
            WHERE spu.tenantId = :tenantId
              AND spu.deleted = false
              AND (:status IS NULL OR spu.status = :status)
              AND (
                  :keyword IS NULL
                  OR LOWER(spu.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                  OR LOWER(spu.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
              )
            """)
    Page<ProductSpu> findManagementPage(
            @Param("tenantId") Long tenantId,
            @Param("status") ProductStatus status,
            @Param("keyword") String keyword,
            org.springframework.data.domain.Pageable pageable);

    long countByImageUrl(String imageUrl);

    long countByImageUrlStartingWith(String imageUrlPrefix);

    @Query("SELECT spu.imageUrl FROM ProductSpu spu WHERE spu.imageUrl IS NOT NULL ORDER BY spu.id")
    List<String> findImageUrls(Pageable pageable);
}

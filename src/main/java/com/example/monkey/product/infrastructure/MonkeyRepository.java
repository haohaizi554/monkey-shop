package com.example.monkey.product.infrastructure;

import com.example.monkey.shared.application.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonkeyRepository extends JpaRepository<Monkey, Long> {
    Page<Monkey> findAllBy(Pageable pageable);

    @Query("""
            SELECT m
            FROM Monkey m
            WHERE (
                :keyword IS NULL
                OR LOWER(m.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(m.breed) LIKE LOWER(CONCAT('%', :keyword, '%'))
            )
            AND (:minPrice IS NULL OR m.price >= :minPrice)
            AND (:maxPrice IS NULL OR m.price <= :maxPrice)
            AND (:inStock IS NULL OR :inStock = false OR m.stock > 0)
            """)
    Page<Monkey> findPage(
            @Param("keyword") String keyword,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("inStock") Boolean inStock,
            Pageable pageable);

    long countByImageUrl(String imageUrl);

    @Query("SELECT m.imageUrl FROM Monkey m WHERE m.imageUrl IS NOT NULL ORDER BY m.id")
    List<String> findImageUrls(Pageable pageable);

    default int deductStock(Long id) {
        return deductStock(id, TenantContext.currentTenantIdOrDefault());
    }

    @Modifying
    @Query("UPDATE Monkey m SET m.stock = m.stock - 1 WHERE m.id = :id AND m.tenantId = :tenantId AND m.stock > 0")
    int deductStock(@Param("id") Long id, @Param("tenantId") Long tenantId);

    default int restoreStock(Long id) {
        return restoreStock(id, TenantContext.currentTenantIdOrDefault());
    }

    @Modifying
    @Query("UPDATE Monkey m SET m.stock = m.stock + 1 WHERE m.id = :id AND m.tenantId = :tenantId")
    int restoreStock(@Param("id") Long id, @Param("tenantId") Long tenantId);
}

package com.example.monkey.product.infrastructure;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonkeyRepository extends JpaRepository<Monkey, Long> {
    Page<Monkey> findAllBy(Pageable pageable);

    long countByImageUrl(String imageUrl);

    @Query("SELECT m.imageUrl FROM Monkey m WHERE m.imageUrl IS NOT NULL ORDER BY m.id")
    List<String> findImageUrls(Pageable pageable);

    @Modifying
    @Query("UPDATE Monkey m SET m.stock = m.stock - 1 WHERE m.id = :id AND m.stock > 0")
    int deductStock(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Monkey m SET m.stock = m.stock + 1 WHERE m.id = :id")
    int restoreStock(@Param("id") Long id);
}

package com.example.monkey.repository;

import com.example.monkey.entity.Monkey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface MonkeyRepository extends JpaRepository<Monkey, Long> {
    long countByImageUrl(String imageUrl);
    @Query("SELECT m.imageUrl FROM Monkey m WHERE m.imageUrl IS NOT NULL")
    List<String> findAllImageUrls();
    @Modifying
    @Query("UPDATE Monkey m SET m.stock = m.stock - 1 WHERE m.id = :id AND m.stock > 0")
    int deductStock(@Param("id") Long id);
    @Modifying
    @Query("UPDATE Monkey m SET m.stock = m.stock + 1 WHERE m.id = :id")
    int restoreStock(@Param("id") Long id);
}

package com.example.monkey.repository;

import com.example.monkey.entity.Monkey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface MonkeyRepository extends JpaRepository<Monkey, Long> {
    long countByImageUrl(String imageUrl);

    @Query("SELECT m.imageUrl FROM Monkey m WHERE m.imageUrl IS NOT NULL")
    List<String> findAllImageUrls();
}
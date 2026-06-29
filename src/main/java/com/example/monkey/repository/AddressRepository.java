package com.example.monkey.repository;

import com.example.monkey.entity.Address;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AddressRepository extends JpaRepository<Address, Long> {
    // 查某人的所有地址
    List<Address> findByUserId(Long userId);

    Page<Address> findByUserId(Long userId, Pageable pageable);

    boolean existsByUserId(Long userId);

    Optional<Address> findByIdAndUserId(Long id, Long userId);

    // 把某人的所有地址设为非默认 (用于设置新默认前的重置)
    @Modifying
    @Transactional
    @Query("UPDATE Address a SET a.isDefault = 0 WHERE a.userId = ?1 AND a.deleted = false")
    void clearDefault(Long userId);
}

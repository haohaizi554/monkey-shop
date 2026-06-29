package com.example.monkey.user.infrastructure;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {
    User findByUsername(String username);

    long countByRole(String role);

    long countByAvatar(String avatar);

    List<User> findByRole(String role);

    // 鏂板锛氭煡鍑烘墍鏈夌敤鎴峰ご鍍忚矾寰?
    @Query("SELECT u.avatar FROM User u WHERE u.avatar IS NOT NULL ORDER BY u.id")
    List<String> findAvatars(Pageable pageable);
}

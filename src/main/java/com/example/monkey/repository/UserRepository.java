package com.example.monkey.repository;

import com.example.monkey.entity.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {
    User findByUsername(String username);

    long countByRole(String role);

    long countByAvatar(String avatar);

    List<User> findByRole(String role);

    // 新增：查出所有用户头像路径
    @Query("SELECT u.avatar FROM User u WHERE u.avatar IS NOT NULL")
    List<String> findAllAvatars();
}

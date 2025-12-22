package com.example.monkey.repository;

import com.example.monkey.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {
    User findByUsername(String username);
    long countByAvatar(String avatar);

    // 新增：查出所有用户头像路径
    @Query("SELECT u.avatar FROM User u WHERE u.avatar IS NOT NULL")
    List<String> findAllAvatars();
}
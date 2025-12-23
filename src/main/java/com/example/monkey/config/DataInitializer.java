package com.example.monkey.config;

import com.example.monkey.entity.Admin;
import com.example.monkey.repository.AdminRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DataInitializer {

    @Bean
    public CommandLineRunner initData(AdminRepository adminRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            if (adminRepository.count() == 0) {
                System.out.println("【初始化】检测到管理员表为空，正在创建默认管理员...");
                Admin admin = new Admin();
                admin.setUsername("admin");
                admin.setPassword(passwordEncoder.encode("123456"));
                admin.setNickname("超级管理员");

                adminRepository.save(admin);
                System.out.println("【初始化】管理员创建成功！账号: admin / 密码: 123456");
            }
        };
    }
}
package com.example.monkey.config;

import com.example.monkey.entity.Admin;
import com.example.monkey.repository.AdminRepository;
import com.example.monkey.security.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Bean
    public CommandLineRunner initData(
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            @Value("${app.admin.username:admin}") String adminUsername,
            @Value("${app.admin.password:}") String adminPassword) {
        return args -> {
            if (adminRepository.count() > 0) {
                return;
            }
            if (!StringUtils.hasText(adminPassword)) {
                throw new IllegalStateException(
                        "ADMIN_INIT_PASSWORD must be set before bootstrapping the first administrator");
            }
            PasswordPolicy.PasswordPolicyResult passwordResult = passwordPolicy.validate(adminPassword);
            if (!passwordResult.valid()) {
                throw new IllegalStateException(
                        "ADMIN_INIT_PASSWORD does not meet policy: "
                                + String.join("; ", passwordResult.violations()));
            }

            Admin admin = new Admin();
            admin.setUsername(adminUsername);
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setNickname("Administrator");
            adminRepository.save(admin);
            log.info("Initial administrator account was created from externalized configuration");
        };
    }
}

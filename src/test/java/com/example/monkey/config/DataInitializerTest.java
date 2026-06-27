package com.example.monkey.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.entity.Admin;
import com.example.monkey.repository.AdminRepository;
import com.example.monkey.security.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock
    private AdminRepository adminRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private DataInitializer dataInitializer;

    @BeforeEach
    void setUp() {
        dataInitializer = new DataInitializer();
    }

    @Test
    void refusesToBootstrapAdminWhenPasswordIsMissing() throws Exception {
        when(adminRepository.count()).thenReturn(0L);
        CommandLineRunner runner = dataInitializer.initData(
                adminRepository, passwordEncoder, new PasswordPolicy(), "admin", "");

        assertThatThrownBy(() -> runner.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_INIT_PASSWORD must be set");
        verify(adminRepository, never()).save(any(Admin.class));
    }

    @Test
    void refusesToBootstrapAdminWhenPasswordIsWeak() throws Exception {
        when(adminRepository.count()).thenReturn(0L);
        CommandLineRunner runner = dataInitializer.initData(
                adminRepository, passwordEncoder, new PasswordPolicy(), "admin", "Password1");

        assertThatThrownBy(() -> runner.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not meet policy");
        verify(adminRepository, never()).save(any(Admin.class));
    }

    @Test
    void bootstrapsAdminWithEncodedStrongPassword() throws Exception {
        when(adminRepository.count()).thenReturn(0L);
        when(passwordEncoder.encode("StrongPass1!")).thenReturn("encoded-password");
        CommandLineRunner runner = dataInitializer.initData(
                adminRepository, passwordEncoder, new PasswordPolicy(), "root-admin", "StrongPass1!");

        runner.run();

        ArgumentCaptor<Admin> adminCaptor = ArgumentCaptor.forClass(Admin.class);
        verify(adminRepository).save(adminCaptor.capture());
        Admin admin = adminCaptor.getValue();
        assertThat(admin.getUsername()).isEqualTo("root-admin");
        assertThat(admin.getPassword()).isEqualTo("encoded-password");
        assertThat(admin.getNickname()).isEqualTo("Administrator");
    }
}

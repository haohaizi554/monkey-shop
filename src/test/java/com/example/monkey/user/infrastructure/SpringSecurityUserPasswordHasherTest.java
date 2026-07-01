package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class SpringSecurityUserPasswordHasherTest {

    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    void hashDelegatesToConfiguredPasswordEncoder() {
        SpringSecurityUserPasswordHasher hasher = new SpringSecurityUserPasswordHasher(passwordEncoder);
        when(passwordEncoder.encode("StrongPass1!")).thenReturn("encoded-password");

        assertThat(hasher.hash("StrongPass1!")).isEqualTo("encoded-password");

        verify(passwordEncoder).encode("StrongPass1!");
    }

    @Test
    void matchesDelegatesToConfiguredPasswordEncoder() {
        SpringSecurityUserPasswordHasher hasher = new SpringSecurityUserPasswordHasher(passwordEncoder);
        when(passwordEncoder.matches("StrongPass1!", "encoded-password")).thenReturn(true);

        assertThat(hasher.matches("StrongPass1!", "encoded-password")).isTrue();

        verify(passwordEncoder).matches("StrongPass1!", "encoded-password");
    }
}

package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.monkey.user.application.dto.PasswordPolicyResponseDto;
import com.example.monkey.user.domain.UserPasswordPolicy;
import com.example.monkey.user.domain.UserPasswordPolicy.PasswordPolicyMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PasswordPolicyQueryServiceTest {

    @Mock
    private UserPasswordPolicy passwordPolicy;

    @Test
    void mapsDomainMetadataToPasswordPolicyResponse() {
        when(passwordPolicy.metadata()).thenReturn(new PasswordPolicyMetadata(10, true, true, true, true, true));

        PasswordPolicyResponseDto result = new PasswordPolicyQueryService(passwordPolicy).metadata();

        assertThat(result).isEqualTo(new PasswordPolicyResponseDto(10, true, true, true, true, true));
    }
}

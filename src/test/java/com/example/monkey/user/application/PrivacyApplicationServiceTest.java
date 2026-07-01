package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.domain.UserRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrivacyApplicationServiceTest {

    @Mock
    private PiiRetentionService piiRetentionService;

    private PrivacyApplicationService privacyApplicationService;

    @BeforeEach
    void setUp() {
        privacyApplicationService = new PrivacyApplicationService(piiRetentionService);
    }

    @Test
    void forgetUserRequiresAuthenticatedUserBeforeDelegating() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> privacyApplicationService.forgetUser(null))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(piiRetentionService);
    }

    @Test
    void forgetUserDelegatesWithRequiredUserId() {
        privacyApplicationService.forgetUser(new SessionUser(7L, UserRoles.USER));

        verify(piiRetentionService).forgetUser(7L);
    }
}

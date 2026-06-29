package com.example.monkey.user.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.user.application.PrivacyApplicationService;
import com.example.monkey.user.domain.UserRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrivacyControllerTest {

    @Mock
    private PrivacyApplicationService privacyApplicationService;

    private PrivacyController controller;

    @BeforeEach
    void setUp() {
        controller = new PrivacyController(privacyApplicationService);
    }

    @Test
    void forgetMeDelegatesCurrentUserScope() {
        SessionUser currentUser = new SessionUser(7L, UserRoles.USER);

        Result<Void> result = controller.forgetMe(currentUser);

        assertThat(result.code()).isEqualTo("OK");
        assertThat(result.data()).isNull();
        verify(privacyApplicationService).forgetUser(currentUser);
    }

    @Test
    void forgetMePropagatesMissingAuthenticatedUserFromApplicationService() {
        doThrow(new BusinessException(ErrorCode.UNAUTHORIZED, "login required"))
                .when(privacyApplicationService)
                .forgetUser(null);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> controller.forgetMe(null))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verify(privacyApplicationService).forgetUser(null);
    }
}

package com.example.monkey.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.user.application.dto.UserProfileResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserProfileApplicationServiceTest {

    @Mock
    private UserService userService;

    private UserProfileApplicationService profileService;

    @BeforeEach
    void setUp() {
        profileService = new UserProfileApplicationService(userService);
    }

    @Test
    void currentUserDelegatesToMaskedProfile() {
        SessionUser currentUser = new SessionUser(7L, "USER");
        UserProfileResponseDto profile =
                new UserProfileResponseDto(true, "USER", "alice", "/avatar.png", "188****8888", false);
        when(userService.getUserInfo(currentUser, false)).thenReturn(profile);

        UserProfileResponseDto result = profileService.currentUser(currentUser);

        assertThat(result).isSameAs(profile);
        verify(userService).getUserInfo(currentUser, false);
    }

    @Test
    void profileDelegatesToDetailedProfile() {
        SessionUser currentUser = new SessionUser(7L, "USER");
        UserProfileResponseDto profile =
                new UserProfileResponseDto(true, "USER", "alice", "/avatar.png", "18888888888", false);
        when(userService.getUserInfo(currentUser, true)).thenReturn(profile);

        UserProfileResponseDto result = profileService.profile(currentUser);

        assertThat(result).isSameAs(profile);
        verify(userService).getUserInfo(currentUser, true);
    }

    @Test
    void updateAvatarRequiresAuthenticatedUserBeforeUpdating() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> profileService.updateAvatar(null, "/avatar/new.png"))
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

        verifyNoInteractions(userService);
    }

    @Test
    void updateAvatarDelegatesWithRequiredUserId() {
        SessionUser currentUser = new SessionUser(7L, "USER");

        profileService.updateAvatar(currentUser, "/avatar/new.png");

        verify(userService).updateAvatar(7L, "/avatar/new.png");
    }
}

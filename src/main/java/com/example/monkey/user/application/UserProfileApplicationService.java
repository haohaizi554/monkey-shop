package com.example.monkey.user.application;

import static com.example.monkey.shared.application.security.AuthenticatedPrincipals.requireUserId;

import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.user.application.dto.UserProfileResponseDto;
import org.springframework.stereotype.Service;

@Service
public class UserProfileApplicationService {

    private final UserService userService;

    public UserProfileApplicationService(UserService userService) {
        this.userService = userService;
    }

    public UserProfileResponseDto currentUser(SessionUser currentUser) {
        return userService.getUserInfo(currentUser, false);
    }

    public UserProfileResponseDto profile(SessionUser currentUser) {
        return userService.getUserInfo(currentUser, true);
    }

    public void updateAvatar(SessionUser currentUser, String avatarPath) {
        userService.updateAvatar(requireUserId(currentUser), avatarPath);
    }
}

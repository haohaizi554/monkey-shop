package com.example.monkey.user.application;

import com.example.monkey.user.application.dto.UserProfileResponseDto;
import com.example.monkey.user.domain.UserAccountStore.UserAccount;

public final class UserDtoAssembler {

    private UserDtoAssembler() {}

    public static UserProfileResponseDto anonymousProfile() {
        return new UserProfileResponseDto(false, null, null, null, null, null);
    }

    public static UserProfileResponseDto adminProfile(
            UserAccount user, String role, String defaultAvatar, boolean details) {
        return new UserProfileResponseDto(
                true,
                role,
                displayName(user),
                avatarOrDefault(user, defaultAvatar),
                details ? "admin account" : null,
                user.passwordChangeRequired());
    }

    public static UserProfileResponseDto userProfile(
            UserAccount user, String role, String defaultAvatar, boolean details) {
        return new UserProfileResponseDto(
                true,
                role,
                user.username(),
                avatarOrDefault(user, defaultAvatar),
                details ? maskPhone(user.phone()) : null,
                user.passwordChangeRequired());
    }

    private static String avatarOrDefault(UserAccount user, String defaultAvatar) {
        return user.avatar() != null ? user.avatar() : defaultAvatar;
    }

    private static String displayName(UserAccount user) {
        return user.nickname() != null && !user.nickname().isBlank() ? user.nickname() : user.username();
    }

    private static String maskPhone(String phone) {
        if (phone == null) {
            return "not bound";
        }
        if (phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}

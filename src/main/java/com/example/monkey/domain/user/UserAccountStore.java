package com.example.monkey.domain.user;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserAccountStore {

    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findById(Long id);

    List<UserAccount> findByRole(String role);

    UserAccount save(UserAccount account);

    List<String> findRecentPasswordHashes(Long userId);

    void recordPasswordHistory(Long userId, String passwordHash, LocalDateTime changedAt);

    record UserAccount(
            Long id,
            String username,
            String passwordHash,
            String phone,
            String email,
            String avatar,
            String role,
            String nickname,
            LocalDateTime passwordLastChangedAt,
            boolean passwordChangeRequired,
            String totpSecret,
            boolean mfaEnabled,
            List<String> authorityNames) {
        public UserAccount {
            authorityNames = authorityNames == null ? List.of() : List.copyOf(authorityNames);
        }

        public UserAccount withAvatar(String newAvatar) {
            return new UserAccount(
                    id,
                    username,
                    passwordHash,
                    phone,
                    email,
                    newAvatar,
                    role,
                    nickname,
                    passwordLastChangedAt,
                    passwordChangeRequired,
                    totpSecret,
                    mfaEnabled,
                    authorityNames);
        }

        public UserAccount withPassword(String newPasswordHash, LocalDateTime changedAt) {
            return new UserAccount(
                    id,
                    username,
                    newPasswordHash,
                    phone,
                    email,
                    avatar,
                    role,
                    nickname,
                    changedAt,
                    false,
                    totpSecret,
                    mfaEnabled,
                    authorityNames);
        }
    }
}

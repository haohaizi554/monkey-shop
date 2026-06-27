package com.example.monkey.service;

import com.example.monkey.entity.Admin;
import com.example.monkey.entity.User;
import com.example.monkey.repository.AdminRepository;
import com.example.monkey.repository.UserRepository;
import com.example.monkey.security.PasswordPolicy;
import com.example.monkey.security.SessionIdentity;
import com.example.monkey.security.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String INVALID_LOGIN_MESSAGE = "username or password is incorrect";
    private static final String DEFAULT_AVATAR = "/images/default_avatar.png";

    private final UserRepository userRepository;
    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final ImageCleanupService imageCleanupService;
    private final PasswordPolicy passwordPolicy;
    private final String missingAccountPasswordHash;

    public UserService(
            UserRepository userRepository,
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            ImageCleanupService imageCleanupService,
            PasswordPolicy passwordPolicy) {
        this(
                userRepository,
                adminRepository,
                passwordEncoder,
                imageCleanupService,
                passwordPolicy,
                passwordEncoder.encode(UUID.randomUUID().toString()));
    }

    UserService(
            UserRepository userRepository,
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            ImageCleanupService imageCleanupService,
            PasswordPolicy passwordPolicy,
            String missingAccountPasswordHash) {
        this.userRepository = userRepository;
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.imageCleanupService = imageCleanupService;
        this.passwordPolicy = passwordPolicy;
        this.missingAccountPasswordHash = missingAccountPasswordHash;
    }

    public String register(String username, String password, String phone, String avatarPath) {
        if (userRepository.findByUsername(username) != null) {
            return "error:username already exists";
        }
        if (adminRepository.findByUsername(username) != null) {
            return "error:username unavailable";
        }
        String passwordPolicyError = passwordPolicy.validateForUserMessage(password);
        if (passwordPolicyError != null) {
            return passwordPolicyError;
        }

        try {
            User user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(password));
            user.setPhone(phone);
            user.setAvatar(avatarPath != null ? avatarPath : DEFAULT_AVATAR);
            userRepository.save(user);
            return "ok";
        } catch (Exception e) {
            log.warn("User registration failed for username {}", username, e);
            return "error:database save failed";
        }
    }

    public String login(String username, String rawPassword, HttpServletRequest request) {
        Admin admin = adminRepository.findByUsername(username);
        User user = userRepository.findByUsername(username);
        String passwordToCheck = rawPassword == null ? "" : rawPassword;

        if (admin != null && passwordEncoder.matches(passwordToCheck, admin.getPassword())) {
            establishSessionIdentity(request, admin.getId(), SessionIdentity.ROLE_ADMIN);
            return "ok:" + SessionIdentity.ROLE_ADMIN;
        }
        if (admin != null) {
            return INVALID_LOGIN_MESSAGE;
        }

        if (user != null && passwordEncoder.matches(passwordToCheck, user.getPassword())) {
            establishSessionIdentity(request, user.getId(), SessionIdentity.ROLE_USER);
            return "ok:" + SessionIdentity.ROLE_USER;
        }
        if (user != null) {
            return INVALID_LOGIN_MESSAGE;
        }
        passwordEncoder.matches(passwordToCheck, missingAccountPasswordHash);
        return INVALID_LOGIN_MESSAGE;
    }

    private static void establishSessionIdentity(HttpServletRequest request, Long id, String role) {
        HttpSession session = request.getSession();
        request.changeSessionId();
        session.setAttribute(SessionIdentity.USER_ID_ATTRIBUTE, id);
        session.setAttribute(SessionIdentity.IDENTITY_ATTRIBUTE, role);
    }

    public Map<String, Object> getUserInfo(SessionUser currentUser, boolean details) {
        Map<String, Object> result = new HashMap<>();
        if (currentUser == null) {
            result.put("isLogin", false);
            return result;
        }

        result.put("isLogin", true);
        result.put("identity", currentUser.role());
        if (currentUser.isAdmin()) {
            Admin admin = adminRepository.findById(currentUser.id()).orElse(null);
            if (admin == null) {
                result.put("isLogin", false);
                return result;
            }
            result.put("username", admin.getNickname());
            result.put("avatar", DEFAULT_AVATAR);
            if (details) {
                result.put("maskedPhone", "admin account");
            }
            return result;
        }

        User user = userRepository.findById(currentUser.id()).orElse(null);
        if (user == null) {
            result.put("isLogin", false);
            return result;
        }
        result.put("username", user.getUsername());
        result.put("avatar", user.getAvatar() != null ? user.getAvatar() : DEFAULT_AVATAR);
        if (details) {
            result.put("maskedPhone", maskPhone(user.getPhone()));
        }
        return result;
    }

    public String updateAvatar(Long userId, String newAvatarPath) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return "error:user not found";
        }
        String oldAvatar = user.getAvatar();
        user.setAvatar(newAvatarPath);
        userRepository.save(user);
        if (oldAvatar != null && !oldAvatar.equals(newAvatarPath)) {
            imageCleanupService.tryDelete(oldAvatar);
        }
        return "ok";
    }

    public String updatePassword(Long userId, String phone, String newPassword) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return "error:user not found";
        }
        return updatePasswordForUser(user, phone, newPassword);
    }

    public String updatePassword(Long userId, String phone, String newPassword, String username) {
        User user = userId != null
                ? userRepository.findById(userId).orElse(null)
                : userRepository.findByUsername(username);
        if (user == null) {
            return "error:user not found";
        }
        return updatePasswordForUser(user, phone, newPassword);
    }

    private String updatePasswordForUser(User user, String phone, String newPassword) {
        if (user.getPhone() == null || !user.getPhone().equals(phone)) {
            return "error:phone verification failed";
        }
        String passwordPolicyError = passwordPolicy.validateForUserMessage(newPassword);
        if (passwordPolicyError != null) {
            return passwordPolicyError;
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return "ok";
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

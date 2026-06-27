package com.example.monkey.service;

import com.example.monkey.entity.Admin;
import com.example.monkey.entity.User;
import com.example.monkey.repository.AdminRepository;
import com.example.monkey.repository.UserRepository;
import com.example.monkey.security.PasswordPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
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

    public UserService(
            UserRepository userRepository,
            AdminRepository adminRepository,
            PasswordEncoder passwordEncoder,
            ImageCleanupService imageCleanupService,
            PasswordPolicy passwordPolicy) {
        this.userRepository = userRepository;
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.imageCleanupService = imageCleanupService;
        this.passwordPolicy = passwordPolicy;
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
        HttpSession session = request.getSession();

        Admin admin = adminRepository.findByUsername(username);
        if (admin != null) {
            if (passwordEncoder.matches(rawPassword, admin.getPassword())) {
                request.changeSessionId();
                session.setAttribute("USER_ID", admin.getId());
                session.setAttribute("IDENTITY", "ADMIN");
                return "ok:ADMIN";
            }
            return INVALID_LOGIN_MESSAGE;
        }

        User user = userRepository.findByUsername(username);
        if (user != null) {
            if (passwordEncoder.matches(rawPassword, user.getPassword())) {
                request.changeSessionId();
                session.setAttribute("USER_ID", user.getId());
                session.setAttribute("IDENTITY", "USER");
                return "ok:USER";
            }
            return INVALID_LOGIN_MESSAGE;
        }
        return INVALID_LOGIN_MESSAGE;
    }

    public Map<String, Object> getUserInfo(HttpSession session, boolean details) {
        Map<String, Object> result = new HashMap<>();
        Long userId = (Long) session.getAttribute("USER_ID");
        String identity = (String) session.getAttribute("IDENTITY");
        if (userId == null || identity == null) {
            result.put("isLogin", false);
            return result;
        }

        result.put("isLogin", true);
        result.put("identity", identity);
        if ("ADMIN".equals(identity)) {
            Admin admin = adminRepository.findById(userId).orElse(null);
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

        User user = userRepository.findById(userId).orElse(null);
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

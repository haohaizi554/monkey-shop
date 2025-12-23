package com.example.monkey.service;

import com.example.monkey.entity.Admin;
import com.example.monkey.entity.User;
import com.example.monkey.repository.AdminRepository;
import com.example.monkey.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;

@Service
public class UserService {

    @Autowired private UserRepository userRepository;
    @Autowired private AdminRepository adminRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ImageCleanupService imageCleanupService;

    // 注册
    public String register(String username, String password, String phone, String avatarPath) {
        if (userRepository.findByUsername(username) != null) return "用户名已存在";
        if (adminRepository.findByUsername(username) != null) return "用户名不可用";
        try {
            User user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(password)); // 加密
            user.setPhone(phone);
            user.setAvatar(avatarPath != null ? avatarPath : "/images/default_avatar.png");
            userRepository.save(user);
            return "ok";
        } catch (Exception e) {
            e.printStackTrace();
            return "数据库保存失败";
        }
    }
    // 登录
    public String login(String username, String rawPassword, HttpSession session) {
        // 1. 查管理员
        Admin admin = adminRepository.findByUsername(username);
        if (admin != null) {
            if (passwordEncoder.matches(rawPassword, admin.getPassword())) {
                session.setAttribute("USER_ID", admin.getId());
                session.setAttribute("IDENTITY", "ADMIN");
                return "ok:ADMIN";
            }
            return "密码错误";
        }
        // 2. 查普通用户
        User user = userRepository.findByUsername(username);
        if (user != null) {
            if (passwordEncoder.matches(rawPassword, user.getPassword())) {
                session.setAttribute("USER_ID", user.getId());
                session.setAttribute("IDENTITY", "USER");
                return "ok:USER";
            }
            return "密码错误";
        }
        return "账号不存在";
    }
    // 获取用户信息 (用于 /me 和 /profile)
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
            if (admin != null) {
                result.put("username", admin.getNickname());
                result.put("avatar", "/images/default_avatar.png");
                if (details) result.put("maskedPhone", "管理员账号");
            } else {
                result.put("isLogin", false);
            }
        } else {
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                result.put("username", user.getUsername());
                result.put("avatar", user.getAvatar() != null ? user.getAvatar() : "/images/default_avatar.png");
                if (details) {
                    String phone = user.getPhone();
                    if (phone != null && phone.length() >= 7) {
                        String masked = phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
                        result.put("maskedPhone", masked);
                    } else {
                        result.put("maskedPhone", phone == null ? "未绑定" : phone);
                    }
                }
            } else {
                result.put("isLogin", false);
            }
        }
        return result;
    }
    // 修改头像
    public String updateAvatar(Long userId, String newAvatarPath) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null) {
            String oldAvatar = user.getAvatar();
            user.setAvatar(newAvatarPath);
            userRepository.save(user);
            // 清理旧图
            if (oldAvatar != null && !oldAvatar.equals(newAvatarPath)) {
                imageCleanupService.tryDelete(oldAvatar);
            }
            return "ok";
        }
        return "error:用户不存在";
    }
    // 修改密码
    public String updatePassword(Long userId, String phone, String newPassword) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return "用户不存在";

        if (user.getPhone() == null || !user.getPhone().equals(phone)) {
            return "手机号验证失败";
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return "ok";
    }

    public String updatePassword(Long userId, String phone, String newPassword, String username) {
        User user;
        if (userId != null) {
            user = userRepository.findById(userId).orElse(null);
        } else {
            user = userRepository.findByUsername(username);
        }
        if (user == null) return "用户不存在";
        if (user.getPhone() == null || !user.getPhone().equals(phone)) {
            return "手机号验证失败";
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return "ok";
    }
}
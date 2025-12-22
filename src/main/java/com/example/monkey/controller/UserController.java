package com.example.monkey.controller;

import com.example.monkey.entity.Admin;
import com.example.monkey.entity.User;
import com.example.monkey.repository.AdminRepository;
import com.example.monkey.repository.UserRepository;
import com.example.monkey.service.CaptchaService;
import com.example.monkey.service.ImageCleanupService; // 引入服务
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminRepository adminRepository;
    @Autowired
    private CaptchaService captchaService;
    @Autowired
    private ImageCleanupService imageCleanupService; // 注入

    @GetMapping("/captcha")
    public void getCaptcha(HttpServletResponse response, HttpSession session) throws IOException {
        captchaService.createCaptcha(response, session);
    }

    @GetMapping("/me")
    public Map<String, Object> getCurrentUser(HttpSession session) {
        return getUserInfo(session, false);
    }

    @GetMapping("/profile")
    public Map<String, Object> getProfile(HttpSession session) {
        return getUserInfo(session, true);
    }

    private Map<String, Object> getUserInfo(HttpSession session, boolean details) {
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

    // --- 修改头像 ---
    @PostMapping("/update-avatar")
    public String updateAvatar(@RequestParam("avatarPath") String newAvatarPath, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        String identity = (String) session.getAttribute("IDENTITY");

        if (userId == null || !"USER".equals(identity)) return "error:未登录或无权限";

        User user = userRepository.findById(userId).orElse(null);
        if (user != null) {
            String oldAvatar = user.getAvatar();

            // 1. 更新
            user.setAvatar(newAvatarPath);
            userRepository.save(user);

            // 2. 尝试清理旧图
            if (oldAvatar != null && !oldAvatar.equals(newAvatarPath)) {
                imageCleanupService.tryDelete(oldAvatar);
            }
            return "ok";
        }
        return "error:用户不存在";
    }

    @PostMapping("/update-password")
    public String updatePassword(@RequestBody Map<String, String> params, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        if (userId == null) return "请先登录";

        String phone = params.get("phone");
        String captcha = params.get("captcha");
        String newPassword = params.get("newPassword");

        if (!captchaService.validate(session, captcha)) return "验证码错误";

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return "用户不存在";

        if (user.getPhone() == null || !user.getPhone().equals(phone)) {
            return "手机号验证失败";
        }

        user.setPassword(newPassword);
        userRepository.save(user);
        session.invalidate();
        return "ok";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "ok";
    }
}
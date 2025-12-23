package com.example.monkey.controller;

import com.example.monkey.config.WebConfig;
import com.example.monkey.entity.Admin;
import com.example.monkey.entity.User;
import com.example.monkey.repository.AdminRepository;
import com.example.monkey.repository.UserRepository;
import com.example.monkey.service.CaptchaService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminRepository adminRepository;
    @Autowired
    private CaptchaService captchaService;

    @GetMapping("/captcha")
    public void getCaptcha(HttpServletResponse response, HttpSession session) throws IOException {
        captchaService.createCaptcha(response, session);
    }

    @PostMapping("/register")
    public String register(
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            @RequestParam("phone") String phone,
            @RequestParam("captcha") String inputCode,
            @RequestParam(value = "avatarFile", required = false) MultipartFile avatarFile,
            HttpSession session
    ) {
        if (!captchaService.validate(session, inputCode)) return "验证码错误";

        if (userRepository.findByUsername(username) != null) return "用户名已存在";
        if (adminRepository.findByUsername(username) != null) return "用户名不可用";

        String avatarPath = "/images/default_avatar.png";

        if (avatarFile != null && !avatarFile.isEmpty()) {
            try {
                String originalFilename = avatarFile.getOriginalFilename();
                String suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
                String newFileName = UUID.randomUUID().toString() + suffix;

                File dest = new File(WebConfig.UPLOAD_PATH + "avatar/" + newFileName);
                if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
                avatarFile.transferTo(dest);

                avatarPath = "/images/avatar/" + newFileName;
            } catch (IOException e) {
                e.printStackTrace();
                return "头像保存失败";
            }
        }

        try {
            User user = new User();
            user.setUsername(username);
            user.setPassword(password);
            user.setPhone(phone);
            user.setAvatar(avatarPath);
            userRepository.save(user);
            return "ok";
        } catch (Exception e) {
            e.printStackTrace();
            return "数据库保存失败";
        }
    }

    @PostMapping("/login")
    public String login(@RequestBody Map<String, String> params, HttpSession session) {
        String username = params.get("username");
        String password = params.get("password");

        Admin admin = adminRepository.findByUsername(username);
        if (admin != null) {
            if (admin.getPassword().equals(password)) {
                session.setAttribute("USER_ID", admin.getId());
                session.setAttribute("IDENTITY", "ADMIN");
                return "ok:ADMIN";
            }
            return "密码错误";
        }

        User user = userRepository.findByUsername(username);
        if (user != null) {
            if (user.getPassword().equals(password)) {
                session.setAttribute("USER_ID", user.getId());
                session.setAttribute("IDENTITY", "USER");
                return "ok:USER";
            }
            return "密码错误";
        }
        return "账号不存在";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestBody Map<String, String> params, HttpSession session) {
        String username = params.get("username");
        String phone = params.get("phone");
        String newPassword = params.get("newPassword");
        String captcha = params.get("captcha"); // 获取验证码
        if (!captchaService.validate(session, captcha)) {
            return "验证码错误";
        }
        User user = userRepository.findByUsername(username);
        if (user == null) return "账号不存在";
        if (user.getPhone() == null || !user.getPhone().equals(phone)) {
            return "手机号验证失败";
        }
        user.setPassword(newPassword);
        userRepository.save(user);
        return "ok";
    }
}
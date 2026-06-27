package com.example.monkey.controller;

import com.example.monkey.service.CaptchaService;
import com.example.monkey.service.FileService;
import com.example.monkey.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired private UserService userService;
    @Autowired private CaptchaService captchaService;
    @Autowired private FileService fileService;
    @Value("${app.security.password-reset-enabled:false}")
    private boolean passwordResetEnabled;

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
        // 1. 校验验证码
        if (!captchaService.validate(session, inputCode)) return "验证码错误";

        // 2. 处理文件
        String avatarPath = null;
        if (avatarFile != null && !avatarFile.isEmpty()) {
            String result = fileService.uploadFile(avatarFile, "avatar");
            if (result.startsWith("error:")) return "头像保存失败";
            // 去掉前缀 "ok:" 或 "cropped:"
            avatarPath = result.substring(result.indexOf(":") + 1);
        }
        // 3. 注册
        return userService.register(username, password, phone, avatarPath);
    }
    @PostMapping("/login")
    public String login(@RequestBody Map<String, String> params, HttpServletRequest request) {
        return userService.login(params.get("username"), params.get("password"), request);
    }
    @PostMapping("/reset-password")
    public String resetPassword(@RequestBody Map<String, String> params, HttpSession session) {
        if (!passwordResetEnabled) {
            return "error:password reset requires an OTP provider";
        }
        String captcha = params.get("captcha");
        if (!captchaService.validate(session, captcha)) return "验证码错误";
        return userService.updatePassword(null, params.get("phone"), params.get("newPassword"), params.get("username"));
    }
}

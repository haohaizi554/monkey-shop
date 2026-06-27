package com.example.monkey.controller;

import com.example.monkey.service.CaptchaService;
import com.example.monkey.service.FileService;
import com.example.monkey.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String PASSWORD_RESET_UNAVAILABLE = "error:password reset requires an OTP provider";

    private final UserService userService;
    private final CaptchaService captchaService;
    private final FileService fileService;

    public AuthController(UserService userService, CaptchaService captchaService, FileService fileService) {
        this.userService = userService;
        this.captchaService = captchaService;
        this.fileService = fileService;
    }

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
            HttpSession session) {
        if (!captchaService.validate(session, inputCode)) {
            return "验证码错误";
        }

        String avatarPath = null;
        if (avatarFile != null && !avatarFile.isEmpty()) {
            String result = fileService.uploadFile(avatarFile, "avatar");
            if (result.startsWith("error:")) {
                return "头像保存失败";
            }
            avatarPath = result.substring(result.indexOf(":") + 1);
        }
        return userService.register(username, password, phone, avatarPath);
    }

    @PostMapping("/login")
    public String login(@RequestBody Map<String, String> params, HttpServletRequest request) {
        return userService.login(params.get("username"), params.get("password"), request);
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestBody Map<String, String> params, HttpSession session) {
        return PASSWORD_RESET_UNAVAILABLE;
    }
}

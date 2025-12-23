package com.example.monkey.controller;

import com.example.monkey.service.CaptchaService;
import com.example.monkey.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired private UserService userService;
    @Autowired private CaptchaService captchaService;

    @GetMapping("/captcha")
    public void getCaptcha(HttpServletResponse response, HttpSession session) throws IOException {
        captchaService.createCaptcha(response, session);
    }

    @GetMapping("/me")
    public Map<String, Object> getCurrentUser(HttpSession session) {
        return userService.getUserInfo(session, false);
    }

    @GetMapping("/profile")
    public Map<String, Object> getProfile(HttpSession session) {
        return userService.getUserInfo(session, true);
    }

    @PostMapping("/update-avatar")
    public String updateAvatar(@RequestParam("avatarPath") String avatarPath, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        if (userId == null) return "error:未登录";
        return userService.updateAvatar(userId, avatarPath);
    }

    @PostMapping("/update-password")
    public String updatePassword(@RequestBody Map<String, String> params, HttpSession session) {
        Long userId = (Long) session.getAttribute("USER_ID");
        if (userId == null) return "请先登录";

        if (!captchaService.validate(session, params.get("captcha"))) return "验证码错误";

        String result = userService.updatePassword(userId, params.get("phone"), params.get("newPassword"), null);
        if ("ok".equals(result)) session.invalidate();
        return result;
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "ok";
    }
}
package com.example.monkey.service;

import com.example.monkey.util.CaptchaUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;

@Service
public class CaptchaService {

    // 生成验证码并写入响应流
    public void createCaptcha(HttpServletResponse response, HttpSession session) throws IOException {
        String code = CaptchaUtil.generateCode();
        session.setAttribute("CAPTCHA_CODE", code);

        // 设置响应头，禁止缓存
        response.setHeader("Pragma", "No-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", 0);
        response.setContentType("image/jpeg");

        BufferedImage image = CaptchaUtil.createImage(code, 100, 40);
        ImageIO.write(image, "JPEG", response.getOutputStream());
    }

    // 校验验证码 (校验成功后立即销毁，防止重复使用)
    public boolean validate(HttpSession session, String inputCode) {
        String sessionCode = (String) session.getAttribute("CAPTCHA_CODE");
        if (sessionCode == null || inputCode == null) return false;

        boolean isValid = sessionCode.equalsIgnoreCase(inputCode);
        if (isValid) {
            session.removeAttribute("CAPTCHA_CODE"); // 验证一次即销毁
        }
        return isValid;
    }
}
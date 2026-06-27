package com.example.monkey.service;

import com.example.monkey.util.CaptchaUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

@Service
public class CaptchaService {
    static final String CAPTCHA_CODE_ATTRIBUTE = "CAPTCHA_CODE";

    public void createCaptcha(HttpServletResponse response, HttpSession session) throws IOException {
        String code = CaptchaUtil.generateCode();
        session.setAttribute(CAPTCHA_CODE_ATTRIBUTE, code);
        response.setHeader("Pragma", "No-cache");
        response.setHeader("Cache-Control", "no-cache");
        response.setDateHeader("Expires", 0);
        response.setContentType("image/jpeg");
        BufferedImage image = CaptchaUtil.createImage(code, 100, 40);
        ImageIO.write(image, "JPEG", response.getOutputStream());
    }

    public boolean validate(HttpSession session, String inputCode) {
        String sessionCode = (String) session.getAttribute(CAPTCHA_CODE_ATTRIBUTE);
        session.removeAttribute(CAPTCHA_CODE_ATTRIBUTE);
        return sessionCode != null && inputCode != null && sessionCode.equalsIgnoreCase(inputCode);
    }
}

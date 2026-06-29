package com.example.monkey.user.application;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.security.SecureRandom;

public final class CaptchaUtil {
    private static final String CAPTCHA_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 4;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CaptchaUtil() {}

    public static BufferedImage createImage(String code, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(240, 240, 240));
            graphics.fillRect(0, 0, width, height);

            for (int i = 0; i < 20; i++) {
                graphics.setColor(
                        new Color(SECURE_RANDOM.nextInt(255), SECURE_RANDOM.nextInt(255), SECURE_RANDOM.nextInt(255)));
                graphics.drawLine(
                        SECURE_RANDOM.nextInt(width),
                        SECURE_RANDOM.nextInt(height),
                        SECURE_RANDOM.nextInt(width),
                        SECURE_RANDOM.nextInt(height));
            }

            graphics.setFont(new Font("Arial", Font.BOLD, 24));
            for (int i = 0; i < code.length(); i++) {
                graphics.setColor(
                        new Color(SECURE_RANDOM.nextInt(150), SECURE_RANDOM.nextInt(150), SECURE_RANDOM.nextInt(150)));
                graphics.drawString(String.valueOf(code.charAt(i)), 20 * i + 10, 28);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    public static String generateCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CAPTCHA_CHARS.charAt(SECURE_RANDOM.nextInt(CAPTCHA_CHARS.length())));
        }
        return sb.toString();
    }
}

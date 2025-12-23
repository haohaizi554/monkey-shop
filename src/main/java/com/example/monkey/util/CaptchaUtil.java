package com.example.monkey.util;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Random;

public class CaptchaUtil {
    // 生成验证码图片
    public static BufferedImage createImage(String code, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();
        // 背景色
        g.setColor(new Color(240, 240, 240));
        g.fillRect(0, 0, width, height);
        // 绘制干扰线
        Random r = new Random();
        for (int i = 0; i < 20; i++) {
            g.setColor(new Color(r.nextInt(255), r.nextInt(255), r.nextInt(255)));
            g.drawLine(r.nextInt(width), r.nextInt(height), r.nextInt(width), r.nextInt(height));
        }
        // 绘制验证码
        g.setFont(new Font("Arial", Font.BOLD, 24));
        for (int i = 0; i < code.length(); i++) {
            g.setColor(new Color(r.nextInt(150), r.nextInt(150), r.nextInt(150)));
            g.drawString(String.valueOf(code.charAt(i)), 20 * i + 10, 28);
        }
        g.dispose();
        return image;
    }
    // 生成随机4位字符
    public static String generateCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < 4; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
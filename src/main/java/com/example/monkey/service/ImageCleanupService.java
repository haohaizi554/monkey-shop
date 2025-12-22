package com.example.monkey.service;

import com.example.monkey.config.WebConfig;
import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class ImageCleanupService {

    @Autowired
    private MonkeyRepository monkeyRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrderRepository orderRepository;

    public void tryDelete(String imagePath) {
        // 1. 基础校验
        if (imagePath == null || imagePath.isEmpty()) return;

        if (imagePath.contains("default_product") || imagePath.contains("default_avatar")) {
            return;
        }

        if (monkeyRepository.countByImageUrl(imagePath) > 0) return;
        if (userRepository.countByAvatar(imagePath) > 0) return;
        if (orderRepository.countByProductImage(imagePath) > 0) return;
        if (orderRepository.countByBuyerAvatar(imagePath) > 0) return;

        try {
            String relativePath = imagePath.replace("/images/", "");
            if (relativePath.contains("..")) return;
            File file = new File(WebConfig.UPLOAD_PATH + relativePath);
            if (file.exists() && file.isFile()) {
                boolean deleted = file.delete();
                if (deleted) {
                    System.out.println("【垃圾回收】成功删除冗余图片: " + imagePath);
                } else {
                    System.err.println("【垃圾回收】文件存在但删除失败: " + imagePath);
                }
            }
        } catch (Exception e) {
            System.err.println("【垃圾回收】删除出错: " + e.getMessage());
        }
    }
}
package com.example.monkey.task;

import com.example.monkey.config.WebConfig;
import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

@Component
public class ImageTask {

    @Autowired
    private MonkeyRepository monkeyRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrderRepository orderRepository;

    //@Scheduled(cron = "0 * * * * ?")    //每分钟
    @Scheduled(cron = "0 0 3 * * ?")  //每天凌晨3点
    public void cleanUpOrphanImages() {
        System.out.println("【定时任务】开始扫描冗余图片...");
        // 1. 构建白名单 (数据库里所有正在使用的图片路径)
        Set<String> whitelist = new HashSet<>();
        whitelist.addAll(monkeyRepository.findAllImageUrls());
        whitelist.addAll(userRepository.findAllAvatars());
        whitelist.addAll(orderRepository.findAllProductImages());
        whitelist.addAll(orderRepository.findAllBuyerAvatars());
        // 2. 扫描硬盘文件夹
        // 这里的路径结构是: static/images/product/ 和 static/images/avatar/
        cleanDirectory(new File(WebConfig.UPLOAD_PATH + "product"), "/images/product/", whitelist);
        cleanDirectory(new File(WebConfig.UPLOAD_PATH + "avatar"), "/images/avatar/", whitelist);
        System.out.println("【定时任务】扫描结束。");
    }
    private void cleanDirectory(File dir, String urlPrefix, Set<String> whitelist) {
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        long gracePeriod = 1 * 60 * 1000;
        for (File file : files) {
            if (file.isFile()) {
                String fileName = file.getName();
                String dbPath = urlPrefix + fileName;
                if (fileName.contains("default_")) continue;
                if (!whitelist.contains(dbPath) && (now - file.lastModified() > gracePeriod)) {
                    boolean deleted = file.delete();
                    if (deleted) {
                        System.out.println("【定时清理】已删除垃圾文件: " + dbPath);
                    }
                }
            }
        }
    }
}
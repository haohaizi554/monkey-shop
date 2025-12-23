package com.example.monkey.service;

import com.example.monkey.config.WebConfig;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

@Service
public class FileService {
    public String uploadFile(MultipartFile file, String type) {
        if (file == null || file.isEmpty()) return "error:空文件";
        try {
            // 1. 读取图片
            BufferedImage originalImage = ImageIO.read(file.getInputStream());
            if (originalImage == null) return "error:图片格式不支持";
            int width = originalImage.getWidth();
            int height = originalImage.getHeight();
            BufferedImage finalImage = originalImage;
            boolean isCropped = false;
            // 2. 自动裁剪逻辑 (仅针对商品图 product)
            if ("product".equals(type) && width != height) {
                int size = Math.min(width, height);
                int x = (width - size) / 2;
                int y = (height - size) / 2;
                finalImage = originalImage.getSubimage(x, y, size, size);
                isCropped = true;
            }
            // 3. 生成路径
            String subDir = "avatar".equals(type) ? "avatar/" : "product/";
            String suffix = ".jpg";
            String originalFilename = file.getOriginalFilename();
            if (originalFilename != null && originalFilename.contains(".")) {
                suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String newFileName = UUID.randomUUID().toString() + suffix;
            File dest = new File(WebConfig.UPLOAD_PATH + subDir + newFileName);
            if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();
            // 4. 保存
            String formatName = suffix.replace(".", "");
            if (formatName.isEmpty()) formatName = "jpg";
            ImageIO.write(finalImage, formatName, dest);
            // 5. 返回结果 (带状态前缀)
            String path = "/images/" + subDir + newFileName;
            return (isCropped ? "cropped:" : "ok:") + path;

        } catch (IOException e) {
            e.printStackTrace();
            return "error:上传处理失败";
        }
    }
}
package com.example.monkey.controller;

import com.example.monkey.config.WebConfig;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    @PostMapping
    public String upload(@RequestParam("file") MultipartFile file, @RequestParam("type") String type) {
        if (file.isEmpty()) return "error:空文件";

        try {
            BufferedImage originalImage = ImageIO.read(file.getInputStream());
            if (originalImage == null) return "error:图片格式不支持";

            int width = originalImage.getWidth();
            int height = originalImage.getHeight();
            boolean isCropped = false;

            BufferedImage finalImage = originalImage;

            if (width != height) {
                int size = Math.min(width, height); // 取短边作为正方形边长
                int x = (width - size) / 2;         // 计算X轴偏移量 (居中)
                int y = (height - size) / 2;        // 计算Y轴偏移量 (居中)

                // 执行裁剪 (保留中间部分)
                finalImage = originalImage.getSubimage(x, y, size, size);
                isCropped = true;
            }

            // 3. 准备保存路径
            String subDir = "avatar".equals(type) ? "avatar/" : "product/";
            String suffix = ".jpg"; // 统一转存为 jpg，避免某些 png 透明底变黑问题，或者保留原后缀

            String originalFilename = file.getOriginalFilename();
            if (originalFilename != null && originalFilename.contains(".")) {
                suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
            }

            String newFileName = UUID.randomUUID().toString() + suffix;
            File dest = new File(WebConfig.UPLOAD_PATH + subDir + newFileName);

            if (!dest.getParentFile().exists()) {
                dest.getParentFile().mkdirs();
            }

            String formatName = suffix.replace(".", "");
            if (formatName.isEmpty()) formatName = "jpg";

            ImageIO.write(finalImage, formatName, dest);

            String path = "/images/" + subDir + newFileName;
            return (isCropped ? "cropped:" : "ok:") + path;

        } catch (IOException e) {
            e.printStackTrace();
            return "error:上传处理失败";
        }
    }
}
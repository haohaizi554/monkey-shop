package com.example.monkey.service;
import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ImageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ImageCleanupService.class);

    private final MonkeyRepository monkeyRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final Path uploadRoot;

    public ImageCleanupService(
            MonkeyRepository monkeyRepository,
            UserRepository userRepository,
            OrderRepository orderRepository,
            @Value("${app.upload.path:uploads/images}") String uploadPath) {
        this.monkeyRepository = monkeyRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.uploadRoot = Path.of(uploadPath).toAbsolutePath().normalize();
    }

    public void tryDelete(String imagePath) {
        if (!StringUtils.hasText(imagePath)) {
            return;
        }
        if (imagePath.contains("default_product") || imagePath.contains("default_avatar")) {
            return;
        }
        if (monkeyRepository.countByImageUrl(imagePath) > 0) {
            return;
        }
        if (userRepository.countByAvatar(imagePath) > 0) {
            return;
        }
        if (orderRepository.countByProductImage(imagePath) > 0) {
            return;
        }
        if (orderRepository.countByBuyerAvatar(imagePath) > 0) {
            return;
        }

        String relativePath = imagePath.replaceFirst("^/images/", "");
        Path file = uploadRoot.resolve(relativePath).normalize();
        if (!file.startsWith(uploadRoot)) {
            log.warn("Rejected image cleanup path outside upload root");
            return;
        }

        try {
            if (Files.isRegularFile(file)) {
                Files.delete(file);
                log.info("Deleted unreferenced image {}", imagePath);
            }
        } catch (IOException e) {
            log.warn("Failed to delete unreferenced image {}", imagePath, e);
        }
    }
}

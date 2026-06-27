package com.example.monkey.task;
import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ImageTask {

    private static final Logger log = LoggerFactory.getLogger(ImageTask.class);
    private static final long GRACE_PERIOD_MS = 24L * 60L * 60L * 1000L;

    private final MonkeyRepository monkeyRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final Path uploadRoot;

    public ImageTask(
            MonkeyRepository monkeyRepository,
            UserRepository userRepository,
            OrderRepository orderRepository,
            @Value("${app.upload.path:uploads/images}") String uploadPath) {
        this.monkeyRepository = monkeyRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.uploadRoot = Path.of(uploadPath).toAbsolutePath().normalize();
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanUpOrphanImages() {
        log.info("Starting orphan image cleanup");
        Set<String> whitelist = new HashSet<>();
        whitelist.addAll(monkeyRepository.findAllImageUrls());
        whitelist.addAll(userRepository.findAllAvatars());
        whitelist.addAll(orderRepository.findAllProductImages());
        whitelist.addAll(orderRepository.findAllBuyerAvatars());
        whitelist.remove(null);

        cleanDirectory(uploadRoot.resolve("product").toFile(), "/images/product/", whitelist);
        cleanDirectory(uploadRoot.resolve("avatar").toFile(), "/images/avatar/", whitelist);
        log.info("Finished orphan image cleanup");
    }

    private void cleanDirectory(File dir, String urlPrefix, Set<String> whitelist) {
        if (!dir.exists() || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String fileName = file.getName();
            String dbPath = urlPrefix + fileName;
            if (fileName.contains("default_")) {
                continue;
            }
            if (!whitelist.contains(dbPath) && (now - file.lastModified() > GRACE_PERIOD_MS) && file.delete()) {
                log.info("Deleted orphan image {}", dbPath);
            }
        }
    }
}

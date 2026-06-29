package com.example.monkey.task;

import com.example.monkey.domain.storage.ImageReferenceService;
import com.example.monkey.domain.storage.StoredImageReferenceReader;
import com.example.monkey.service.ImageVariantService;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ImageTask {

    private static final Logger log = LoggerFactory.getLogger(ImageTask.class);

    private final StoredImageReferenceReader storedImageReferenceReader;
    private final ImageReferenceService imageReferenceService;
    private final Path uploadRoot;
    private final Duration gracePeriod;

    public ImageTask(
            StoredImageReferenceReader storedImageReferenceReader,
            ImageReferenceService imageReferenceService,
            @Value("${app.upload.path:uploads/images}") String uploadPath,
            @Value("${app.upload.cleanup.grace-period:PT24H}") Duration gracePeriod) {
        this.storedImageReferenceReader = storedImageReferenceReader;
        this.imageReferenceService = imageReferenceService;
        this.uploadRoot = Path.of(uploadPath).toAbsolutePath().normalize();
        this.gracePeriod = gracePeriod;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    @SchedulerLock(
            name = "monkeyshop.image.cleanup",
            lockAtMostFor = "${app.upload.cleanup.lock-at-most-for:PT30M}",
            lockAtLeastFor = "${app.upload.cleanup.lock-at-least-for:PT1M}")
    public void cleanUpOrphanImages() {
        log.info("Starting orphan image cleanup");
        Set<String> whitelist = storedImageReferenceReader.findAllReferencedImagePaths();
        imageReferenceService.rebuild(whitelist);

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
            String canonicalDbPath = ImageVariantService.canonicalPathForVariant(dbPath);
            if (fileName.contains("default_")) {
                continue;
            }
            if (!whitelist.contains(canonicalDbPath)
                    && !imageReferenceService.hasReferences(canonicalDbPath)
                    && (now - file.lastModified() > gracePeriod.toMillis())
                    && file.delete()) {
                log.info("Deleted orphan image {}", dbPath);
            }
        }
    }
}

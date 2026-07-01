package com.example.monkey.shared.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.example.monkey.shared.domain.storage.StoredImageReferenceReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImageTaskTest {

    @TempDir
    Path uploadRoot;

    @Mock
    private StoredImageReferenceReader storedImageReferenceReader;

    @Test
    void rebuildsReferenceCountsAndDeletesExpiredUnreferencedImages() throws Exception {
        Path referenced = writeOldImage("product/kept.png");
        Path referencedVariant = writeOldImage("product/kept.png@320w.webp");
        Path orphan = writeOldImage("product/orphan.png");
        Path orphanVariant = writeOldImage("product/orphan.png@320w.webp");
        InMemoryImageReferenceService referenceService = new InMemoryImageReferenceService();
        doAnswer(invocation -> {
                    Consumer<String> consumer = invocation.getArgument(0);
                    consumer.accept("/images/product/kept.png");
                    return null;
                })
                .when(storedImageReferenceReader)
                .forEachReferencedImagePath(any());
        ImageTask task = new ImageTask(
                storedImageReferenceReader, referenceService, uploadRoot.toString(), Duration.ofHours(24));

        task.cleanUpOrphanImages();

        assertThat(referenced).isRegularFile();
        assertThat(referencedVariant).isRegularFile();
        assertThat(orphan).doesNotExist();
        assertThat(orphanVariant).doesNotExist();
        assertThat(referenceService.referenceCount("/images/product/kept.png")).isEqualTo(1L);
    }

    @Test
    void cleanupTaskUsesDistributedSchedulerLock() throws Exception {
        Method method = ImageTask.class.getDeclaredMethod("cleanUpOrphanImages");

        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("monkeyshop.image.cleanup");
        assertThat(lock.lockAtMostFor()).isEqualTo("${app.upload.cleanup.lock-at-most-for:PT30M}");
        assertThat(lock.lockAtLeastFor()).isEqualTo("${app.upload.cleanup.lock-at-least-for:PT1M}");
    }

    private Path writeOldImage(String relativePath) throws Exception {
        Path image = uploadRoot.resolve(relativePath);
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        Files.setLastModifiedTime(image, FileTime.from(Instant.now().minusSeconds(25L * 60L * 60L)));
        return image;
    }
}

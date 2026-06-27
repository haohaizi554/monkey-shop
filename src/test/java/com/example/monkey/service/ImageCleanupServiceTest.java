package com.example.monkey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImageCleanupServiceTest {

    @TempDir
    Path uploadRoot;

    @Mock
    private MonkeyRepository monkeyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderRepository orderRepository;

    private ImageCleanupService imageCleanupService;

    @BeforeEach
    void setUp() {
        imageCleanupService = new ImageCleanupService(
                monkeyRepository, userRepository, orderRepository, uploadRoot.toString());
    }

    @Test
    void deletesUnreferencedImageInsideConfiguredRoot() throws IOException {
        Path image = uploadRoot.resolve("avatar/test.png");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");
        stubNoReferences("/images/avatar/test.png");

        imageCleanupService.tryDelete("/images/avatar/test.png");

        assertThat(image).doesNotExist();
    }

    @Test
    void refusesCleanupPathTraversalOutsideConfiguredRoot() throws IOException {
        Path outsideImage = uploadRoot.getParent().resolve("outside.png");
        Files.writeString(outsideImage, "image");
        String traversal = "/images/../outside.png";
        stubNoReferences(traversal);

        imageCleanupService.tryDelete(traversal);

        assertThat(outsideImage).isRegularFile();
    }

    private void stubNoReferences(String imagePath) {
        when(monkeyRepository.countByImageUrl(imagePath)).thenReturn(0L);
        when(userRepository.countByAvatar(imagePath)).thenReturn(0L);
        when(orderRepository.countByProductImage(imagePath)).thenReturn(0L);
        when(orderRepository.countByBuyerAvatar(imagePath)).thenReturn(0L);
    }
}

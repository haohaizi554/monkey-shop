package com.example.monkey.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.monkey.repository.MonkeyRepository;
import com.example.monkey.repository.OrderRepository;
import com.example.monkey.repository.UserRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JpaStoredImageReferenceReaderTest {

    @Mock
    private MonkeyRepository monkeyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrderRepository orderRepository;

    @Test
    void readsStoredImageReferencesAcrossProductsUsersAndOrderSnapshots() {
        JpaStoredImageReferenceReader reader =
                new JpaStoredImageReferenceReader(monkeyRepository, userRepository, orderRepository);
        when(monkeyRepository.findAllImageUrls()).thenReturn(Arrays.asList("/images/product/momo.png", null, " "));
        when(userRepository.findAllAvatars()).thenReturn(List.of("/images/avatar/user.png"));
        when(orderRepository.findAllProductImages())
                .thenReturn(List.of("/images/product/momo.png", "/images/product/order-snapshot.png"));
        when(orderRepository.findAllBuyerAvatars()).thenReturn(List.of("/images/avatar/buyer.png"));

        Set<String> imagePaths = reader.findAllReferencedImagePaths();

        assertThat(imagePaths)
                .containsExactly(
                        "/images/product/momo.png",
                        "/images/avatar/user.png",
                        "/images/product/order-snapshot.png",
                        "/images/avatar/buyer.png");
        verify(monkeyRepository).findAllImageUrls();
        verify(userRepository).findAllAvatars();
        verify(orderRepository).findAllProductImages();
        verify(orderRepository).findAllBuyerAvatars();
    }
}

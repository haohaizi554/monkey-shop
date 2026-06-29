package com.example.monkey.user.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class UserImageReferenceSourceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void reportsUsedWhenUserAvatarReferencesPath() {
        UserImageReferenceSource source = new UserImageReferenceSource(userRepository, 2);
        when(userRepository.countByAvatar("/images/avatar/used.png")).thenReturn(1L);

        boolean used = source.isUsed("/images/avatar/used.png");

        assertThat(used).isTrue();
        verify(userRepository).countByAvatar("/images/avatar/used.png");
    }

    @Test
    void clampsBatchSizeAndScansAvatarReferences() {
        UserImageReferenceSource source = new UserImageReferenceSource(userRepository, 0);
        when(userRepository.findAvatars(PageRequest.of(0, 1))).thenReturn(List.of("/images/avatar/user.png"));
        when(userRepository.findAvatars(PageRequest.of(1, 1))).thenReturn(List.of());
        List<String> imagePaths = new ArrayList<>();

        source.forEachReferencedImagePath(imagePaths::add);

        assertThat(imagePaths).containsExactly("/images/avatar/user.png");
        verify(userRepository).findAvatars(PageRequest.of(0, 1));
        verify(userRepository).findAvatars(PageRequest.of(1, 1));
    }
}

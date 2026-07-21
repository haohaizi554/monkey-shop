package com.example.monkey.shared.interfaces.storage.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PresignedGetUrlRequestDtoTest {

    @Test
    void classifiesNormalizedAvatarAndProductKeys() {
        PresignedGetUrlRequestDto avatar = new PresignedGetUrlRequestDto("///avatar\\42\\profile.webp");
        PresignedGetUrlRequestDto product = new PresignedGetUrlRequestDto("\\product\\7\\cover.webp");

        assertThat(avatar.avatarObject()).isTrue();
        assertThat(avatar.productObject()).isFalse();
        assertThat(product.avatarObject()).isFalse();
        assertThat(product.productObject()).isTrue();
    }

    @Test
    void nullAndUnscopedKeysAreNotClassifiedAsManagedObjects() {
        for (String objectKey : new String[] {null, "", "/documents/readme.txt"}) {
            PresignedGetUrlRequestDto request = new PresignedGetUrlRequestDto(objectKey);
            assertThat(request.avatarObject()).isFalse();
            assertThat(request.productObject()).isFalse();
        }
    }
}

package com.example.monkey.domain.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.example.monkey.shared.api.ErrorCode;
import com.example.monkey.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

class ObjectStorageKeyTest {

    @Test
    void normalizesLeadingSlashesAndWindowsSeparators() {
        assertThat(ObjectStorageKey.normalize("\\avatar\\alice.png")).isEqualTo("avatar/alice.png");
        assertThat(ObjectStorageKey.normalize("/product/item.png")).isEqualTo("product/item.png");
    }

    @Test
    void rejectsBlankKeys() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> ObjectStorageKey.normalize(" "))
                .withMessage("object key is required")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    void rejectsTraversalAndDirectoryKeys() {
        assertInvalid("../evil.png");
        assertInvalid("avatar/");
        assertInvalid("/");
    }

    private static void assertInvalid(String objectKey) {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> ObjectStorageKey.normalize(objectKey))
                .withMessage("invalid object key")
                .satisfies(exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }
}

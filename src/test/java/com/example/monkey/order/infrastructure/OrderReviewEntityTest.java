package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.infrastructure.privacy.EncryptedStringAttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class OrderReviewEntityTest {

    @Test
    void reviewContentIsEncryptedAndSizedForCiphertext() throws NoSuchFieldException {
        Field content = OrderReviewEntity.class.getDeclaredField("content");

        Convert convert = content.getAnnotation(Convert.class);
        Column column = content.getAnnotation(Column.class);

        assertThat(convert.converter()).isEqualTo(EncryptedStringAttributeConverter.class);
        assertThat(column.length()).isGreaterThanOrEqualTo(8192);
    }
}

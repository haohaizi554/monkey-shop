package com.example.monkey.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.order.infrastructure.Order;
import com.example.monkey.product.infrastructure.Monkey;
import jakarta.persistence.Column;
import jakarta.persistence.Version;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class OptimisticLockingMappingTest {

    @Test
    void monkeyUsesNonNullableJpaVersionColumn() throws NoSuchFieldException {
        assertVersionColumn(Monkey.class);
    }

    @Test
    void orderUsesNonNullableJpaVersionColumn() throws NoSuchFieldException {
        assertVersionColumn(Order.class);
    }

    private static void assertVersionColumn(Class<?> entityType) throws NoSuchFieldException {
        Field version = entityType.getDeclaredField("version");

        assertThat(Arrays.stream(entityType.getDeclaredFields())
                        .filter(field -> field.isAnnotationPresent(Version.class))
                        .map(Field::getName))
                .containsExactly("version");
        assertThat(version.getType()).isEqualTo(Long.class);
        assertThat(version.isAnnotationPresent(Version.class)).isTrue();
        assertThat(version.getAnnotation(Column.class).nullable()).isFalse();
    }
}

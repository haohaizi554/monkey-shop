package com.example.monkey.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class OrderRepositoryTransitionQueryTest {

    @Test
    void lifecycleTransitionsIgnoreUserVisibilityButKeepTenantAndDeletionGuards() throws Exception {
        assertLifecycleQuery(method("transitionStatus", Long.class, String.class, String.class, Long.class));
        assertLifecycleQuery(method(
                "transitionStatusWithShippingTime",
                Long.class,
                String.class,
                String.class,
                LocalDateTime.class,
                Long.class));
    }

    private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return OrderRepository.class.getDeclaredMethod(name, parameterTypes);
    }

    private static void assertLifecycleQuery(Method method) {
        String query = method.getAnnotation(Query.class).value();

        assertThat(query)
                .contains("o.tenantId = :tenantId")
                .contains("o.status = :expectedStatus")
                .contains("o.deleted = false")
                .doesNotContain("userHidden");
    }
}

package com.example.monkey.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ControllerAuthorizationDeclarationTest {

    private static final List<Class<?>> CONTROLLERS = restControllers();

    @Test
    void everyControllerMappingDeclaresMethodSecurityIntent() {
        List<String> missingDeclarations = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (isMappedEndpoint(method) && !method.isAnnotationPresent(PreAuthorize.class)) {
                    missingDeclarations.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(missingDeclarations).isEmpty();
    }

    @Test
    void nonPublicControllerMappingsUsePermissionAuthorities() {
        List<String> weakDeclarations = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
                if (isMappedEndpoint(method)
                        && preAuthorize != null
                        && !"permitAll()".equals(preAuthorize.value())
                        && (preAuthorize.value().contains("hasRole(")
                                || preAuthorize.value().contains("hasAnyRole(")
                                || "isAuthenticated()".equals(preAuthorize.value()))) {
                    weakDeclarations.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(weakDeclarations).isEmpty();
    }

    @Test
    void taskSixEndpointsDeclareTheExpectedMethodBoundary() {
        assertThat(methodNamed(com.example.monkey.product.interfaces.CatalogController.class, "categoryTree")
                        .getAnnotation(GetMapping.class)
                        .value())
                .containsExactlyInAnyOrder("/categories", "/categories/tree");
        assertAuthorization(
                com.example.monkey.product.interfaces.CatalogController.class, "categoryTree", "permitAll()");
        assertAuthorization(com.example.monkey.product.interfaces.CatalogController.class, "getSpu", "permitAll()");
        assertAuthorization(com.example.monkey.product.interfaces.CatalogController.class, "quotePrice", "permitAll()");
        assertAuthorization(
                com.example.monkey.product.interfaces.CatalogController.class,
                "createSpu",
                "hasAuthority('PRODUCT_MANAGE')");
        assertAuthorization(
                com.example.monkey.product.interfaces.CatalogController.class,
                "transitionStatus",
                "hasAuthority('PRODUCT_MANAGE')");
        assertAuthorization(
                com.example.monkey.inventory.interfaces.InventoryController.class,
                "release",
                "hasAuthority('ORDER_MANAGE')");
        assertAuthorization(
                com.example.monkey.inventory.interfaces.InventoryController.class,
                "compensate",
                "hasAuthority('ORDER_MANAGE')");
        assertAuthorization(
                com.example.monkey.marketing.interfaces.MarketingController.class,
                "returnCoupon",
                "hasAnyAuthority('ORDER_CREATE', 'ORDER_MANAGE')");
        assertAuthorization(com.example.monkey.payment.interfaces.PaymentController.class, "callback", "permitAll()");
        assertAuthorization(
                com.example.monkey.logistics.interfaces.LogisticsController.class, "webhook", "permitAll()");
    }

    private static boolean isMappedEndpoint(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(RequestMapping.class);
    }

    private static List<Class<?>> restControllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return Stream.of("com.example.monkey")
                .flatMap(basePackage -> scanner.findCandidateComponents(basePackage).stream())
                .map(BeanDefinition::getBeanClassName)
                .filter(Objects::nonNull)
                .filter(className -> !className.contains("$"))
                .map(ControllerAuthorizationDeclarationTest::loadClass)
                .sorted(java.util.Comparator.comparing(Class::getName))
                .toList();
    }

    private static void assertAuthorization(Class<?> controller, String methodName, String expected) {
        Method method = methodNamed(controller, methodName);
        assertThat(method.getAnnotation(PreAuthorize.class))
                .as(controller.getSimpleName() + "#" + methodName + " authorization")
                .isNotNull()
                .extracting(PreAuthorize::value)
                .isEqualTo(expected);
    }

    private static Method methodNamed(Class<?> controller, String methodName) {
        List<Method> methods = Stream.of(controller.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .toList();
        assertThat(methods).as(controller.getSimpleName() + "#" + methodName).hasSize(1);
        return methods.getFirst();
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to load controller class " + className, e);
        }
    }
}

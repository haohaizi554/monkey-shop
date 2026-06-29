package com.example.monkey.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        List<String> roleOnlyDeclarations = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
                if (isMappedEndpoint(method)
                        && preAuthorize != null
                        && !"permitAll()".equals(preAuthorize.value())
                        && (preAuthorize.value().contains("hasRole(")
                                || preAuthorize.value().contains("hasAnyRole("))) {
                    roleOnlyDeclarations.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(roleOnlyDeclarations).isEmpty();
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
        return scanner.findCandidateComponents("com.example.monkey.controller").stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(Objects::nonNull)
                .map(ControllerAuthorizationDeclarationTest::loadClass)
                .sorted(java.util.Comparator.comparing(Class::getName))
                .toList();
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to load controller class " + className, e);
        }
    }
}

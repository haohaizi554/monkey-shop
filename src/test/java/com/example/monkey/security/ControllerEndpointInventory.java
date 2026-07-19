package com.example.monkey.security;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

public final class ControllerEndpointInventory {

    private static final String CANONICAL_API_PREFIX = "/api/v1";

    private ControllerEndpointInventory() {}

    public static List<Endpoint> canonicalApiEndpoints() {
        Set<Endpoint> endpoints = new LinkedHashSet<>();
        for (Class<?> controller : restControllers()) {
            List<String> basePaths =
                    mappingPaths(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class)).stream()
                            .filter(path -> path.startsWith(CANONICAL_API_PREFIX))
                            .toList();
            for (Method handler : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(handler, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                PreAuthorize authorization = AnnotatedElementUtils.findMergedAnnotation(handler, PreAuthorize.class);
                for (String basePath : basePaths) {
                    for (String methodPath : mappingPaths(mapping)) {
                        for (RequestMethod requestMethod : mapping.method()) {
                            endpoints.add(new Endpoint(
                                    requestMethod.name(),
                                    normalizePath(basePath, methodPath),
                                    controller.getSimpleName(),
                                    handler.getName(),
                                    authorization == null ? "" : authorization.value()));
                        }
                    }
                }
            }
        }
        endpoints.add(
                new Endpoint("POST", "/api/v1/users/logout", "SecurityFilterChain", "logout", "authenticated + CSRF"));
        return endpoints.stream().sorted().toList();
    }

    private static List<String> mappingPaths(RequestMapping mapping) {
        if (mapping == null) {
            return List.of("");
        }
        List<String> paths = Stream.concat(Arrays.stream(mapping.path()), Arrays.stream(mapping.value()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return paths.isEmpty() ? List.of("") : paths;
    }

    private static String normalizePath(String basePath, String methodPath) {
        String combined = (basePath + "/" + methodPath).replaceAll("/{2,}", "/");
        if (combined.length() > 1 && combined.endsWith("/")) {
            return combined.substring(0, combined.length() - 1);
        }
        return combined;
    }

    private static List<Class<?>> restControllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.example.monkey")) {
            String className = definition.getBeanClassName();
            if (className != null && !className.contains("$")) {
                controllers.add(loadClass(className));
            }
        }
        return controllers.stream().sorted(Comparator.comparing(Class::getName)).toList();
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Failed to load controller class " + className, exception);
        }
    }

    public record Endpoint(String method, String path, String controller, String handler, String authorization)
            implements Comparable<Endpoint> {

        @Override
        public int compareTo(Endpoint other) {
            return Comparator.comparing(Endpoint::path)
                    .thenComparing(Endpoint::method)
                    .thenComparing(Endpoint::controller)
                    .thenComparing(Endpoint::handler)
                    .compare(this, other);
        }

        public String key() {
            return method + " " + path;
        }

        public String handlerKey() {
            return controller + "#" + handler;
        }
    }
}

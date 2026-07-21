package com.example.monkey.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.admin.interfaces.StatsController;
import com.example.monkey.admin.interfaces.dto.AuditTraceRequestDto;
import com.example.monkey.admin.interfaces.dto.StatsQueryRequestDto;
import com.example.monkey.shared.application.security.SessionUser;
import com.example.monkey.shared.interfaces.dto.Result;
import com.example.monkey.shared.interfaces.storage.UploadController;
import com.example.monkey.shared.interfaces.storage.dto.UploadFileRequestDto;
import com.example.monkey.shared.interfaces.storage.dto.UploadRequestDto;
import com.example.monkey.user.application.UserService;
import com.example.monkey.user.domain.LoginAttemptPolicy;
import com.example.monkey.user.domain.LoginAttemptState;
import com.example.monkey.user.domain.PasswordResetChallengeService;
import com.example.monkey.user.domain.UserPasswordPolicy;
import com.example.monkey.user.interfaces.AuthController;
import com.example.monkey.user.interfaces.UserController;
import com.example.monkey.user.interfaces.dto.RegisterRequestDto;
import com.example.monkey.user.interfaces.dto.UserAvatarRequestDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class ControllerContractBoundaryTest {

    private static final Pattern RESPONSE_DTO_CONSTRUCTOR = Pattern.compile("\\bnew\\s+[A-Za-z0-9]+ResponseDto\\s*\\(");
    private static final String CONTROLLER_SCAN_ROOT = "com.example.monkey";

    private static final List<Class<?>> CONTROLLERS = restControllers();

    @Test
    void mappedEndpointsDoNotExposeRawMapContracts() {
        List<String> rawMapEndpoints = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isMappedEndpoint(method)) {
                    continue;
                }
                if (Map.class.isAssignableFrom(method.getReturnType())) {
                    rawMapEndpoints.add(controller.getSimpleName() + "#" + method.getName() + " return");
                }
                for (Class<?> parameterType : method.getParameterTypes()) {
                    if (Map.class.isAssignableFrom(parameterType)) {
                        rawMapEndpoints.add(controller.getSimpleName() + "#" + method.getName() + " parameter");
                    }
                }
            }
        }

        assertThat(rawMapEndpoints).isEmpty();
    }

    @Test
    void resultMigratedControllersDoNotExposeStringBusinessContracts() {
        List<String> stringEndpoints = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (isMappedEndpoint(method) && String.class.equals(method.getReturnType())) {
                    stringEndpoints.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(stringEndpoints).isEmpty();
    }

    @Test
    void restControllerEndpointsReturnResultEnvelopeOrExplicitStream() {
        List<String> unenvelopedEndpoints = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isMappedEndpoint(method)
                        || Result.class.equals(method.getReturnType())
                        || isExplicitStreamEndpoint(method)) {
                    continue;
                }
                unenvelopedEndpoints.add(controller.getSimpleName() + "#" + method.getName());
            }
        }

        assertThat(unenvelopedEndpoints).isEmpty();
    }

    @Test
    void requestBodyEndpointsValidateDtoContracts() {
        List<String> invalidRequestBodies = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                Annotation[][] parameterAnnotations = method.getParameterAnnotations();
                Class<?>[] parameterTypes = method.getParameterTypes();
                for (int parameterIndex = 0; parameterIndex < parameterAnnotations.length; parameterIndex++) {
                    if (hasAnnotation(parameterAnnotations[parameterIndex], RequestBody.class)
                            && (!hasAnnotation(parameterAnnotations[parameterIndex], Valid.class)
                                    || !parameterTypes[parameterIndex]
                                            .getSimpleName()
                                            .endsWith("RequestDto"))) {
                        invalidRequestBodies.add(
                                controller.getSimpleName() + "#" + method.getName() + " parameter " + parameterIndex);
                    }
                }
            }
        }

        assertThat(invalidRequestBodies).isEmpty();
    }

    @Test
    void modelAttributeEndpointsValidateDtoContracts() {
        List<String> invalidModelAttributes = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isMappedEndpoint(method)) {
                    continue;
                }
                Annotation[][] parameterAnnotations = method.getParameterAnnotations();
                Class<?>[] parameterTypes = method.getParameterTypes();
                for (int parameterIndex = 0; parameterIndex < parameterAnnotations.length; parameterIndex++) {
                    if (!hasAnnotation(parameterAnnotations[parameterIndex], ModelAttribute.class)) {
                        continue;
                    }
                    if (!hasAnnotation(parameterAnnotations[parameterIndex], Valid.class)
                            || !parameterTypes[parameterIndex].getSimpleName().endsWith("RequestDto")) {
                        invalidModelAttributes.add(
                                controller.getSimpleName() + "#" + method.getName() + " parameter " + parameterIndex);
                    }
                }
            }
        }

        assertThat(invalidModelAttributes).isEmpty();
    }

    @Test
    void mappedEndpointsDoNotUseRequestParamContracts() {
        List<String> requestParamEndpoints = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isMappedEndpoint(method)) {
                    continue;
                }
                Annotation[][] parameterAnnotations = method.getParameterAnnotations();
                for (int parameterIndex = 0; parameterIndex < parameterAnnotations.length; parameterIndex++) {
                    if (hasAnnotation(parameterAnnotations[parameterIndex], RequestParam.class)) {
                        requestParamEndpoints.add(
                                controller.getSimpleName() + "#" + method.getName() + " parameter " + parameterIndex);
                    }
                }
            }
        }

        assertThat(requestParamEndpoints).isEmpty();
    }

    @Test
    void authRegisterUsesSingleValidatedRequestDtoContract() throws NoSuchMethodException {
        Method register =
                AuthController.class.getDeclaredMethod("register", RegisterRequestDto.class, HttpServletRequest.class);
        assertThat(register.getReturnType()).isEqualTo(Result.class);
        assertThat(hasAnnotation(register.getParameterAnnotations()[0], ModelAttribute.class))
                .isTrue();
        assertThat(hasAnnotation(register.getParameterAnnotations()[0], Valid.class))
                .isTrue();
    }

    @Test
    void userReadEndpointsReturnResultEnvelope() throws NoSuchMethodException {
        assertThat(UserController.class
                        .getDeclaredMethod("getCurrentUser", SessionUser.class)
                        .getReturnType())
                .isEqualTo(Result.class);
        assertThat(UserController.class
                        .getDeclaredMethod("getProfile", SessionUser.class)
                        .getReturnType())
                .isEqualTo(Result.class);
    }

    @Test
    void userAvatarWriteUsesValidatedRequestDtoContract() throws NoSuchMethodException {
        Method updateAvatar =
                UserController.class.getDeclaredMethod("updateAvatar", UserAvatarRequestDto.class, SessionUser.class);
        assertThat(updateAvatar.getReturnType()).isEqualTo(Result.class);
        assertThat(hasAnnotation(updateAvatar.getParameterAnnotations()[0], RequestBody.class))
                .isTrue();
        assertThat(hasAnnotation(updateAvatar.getParameterAnnotations()[0], Valid.class))
                .isTrue();
    }

    @Test
    void uploadWritesUseValidatedMultipartRequestDtoContracts() throws NoSuchMethodException {
        assertValidatedModelAttribute(
                UploadController.class.getDeclaredMethod("upload", UploadRequestDto.class), UploadRequestDto.class);
        assertValidatedModelAttribute(
                UploadController.class.getDeclaredMethod("uploadAvatar", UploadFileRequestDto.class),
                UploadFileRequestDto.class);
        assertValidatedModelAttribute(
                UploadController.class.getDeclaredMethod("uploadProduct", UploadFileRequestDto.class),
                UploadFileRequestDto.class);
    }

    @Test
    void statsReadsUseValidatedRequestDtoContracts() throws NoSuchMethodException {
        assertValidatedModelAttribute(
                StatsController.class.getDeclaredMethod("getAuditTrace", AuditTraceRequestDto.class),
                AuditTraceRequestDto.class);
        assertValidatedModelAttribute(
                StatsController.class.getDeclaredMethod("getStats", StatsQueryRequestDto.class),
                StatsQueryRequestDto.class);
    }

    @Test
    void userWriteServicesDoNotExposeStringBusinessContracts() throws NoSuchMethodException {
        assertThat(UserService.class
                        .getDeclaredMethod("register", String.class, String.class, String.class, String.class)
                        .getReturnType())
                .isEqualTo(Void.TYPE);
        assertThat(UserService.class
                        .getDeclaredMethod(
                                "register", String.class, String.class, String.class, String.class, String.class)
                        .getReturnType())
                .isEqualTo(Void.TYPE);
        assertThat(UserService.class
                        .getDeclaredMethod("updateAvatar", Long.class, String.class)
                        .getReturnType())
                .isEqualTo(Void.TYPE);
        assertThat(UserService.class
                        .getDeclaredMethod("updatePassword", Long.class, String.class, String.class)
                        .getReturnType())
                .isEqualTo(Void.TYPE);
        assertThat(UserService.class
                        .getDeclaredMethod("updatePassword", Long.class, String.class, String.class, String.class)
                        .getReturnType())
                .isEqualTo(Void.TYPE);
        assertThat(UserService.class
                        .getDeclaredMethod("resetPasswordAfterOtp", String.class, String.class, String.class)
                        .getReturnType())
                .isEqualTo(Void.TYPE);
    }

    @Test
    void passwordWriteServicesAreTransactionBoundForHistoryConsistency() throws NoSuchMethodException {
        assertThat(UserService.class
                        .getDeclaredMethod("register", String.class, String.class, String.class, String.class)
                        .isAnnotationPresent(Transactional.class))
                .isTrue();
        assertThat(UserService.class
                        .getDeclaredMethod(
                                "register", String.class, String.class, String.class, String.class, String.class)
                        .isAnnotationPresent(Transactional.class))
                .isTrue();
        assertThat(UserService.class
                        .getDeclaredMethod("updatePassword", Long.class, String.class, String.class)
                        .isAnnotationPresent(Transactional.class))
                .isTrue();
        assertThat(UserService.class
                        .getDeclaredMethod("updatePassword", Long.class, String.class, String.class, String.class)
                        .isAnnotationPresent(Transactional.class))
                .isTrue();
        assertThat(UserService.class
                        .getDeclaredMethod("resetPasswordAfterOtp", String.class, String.class, String.class)
                        .isAnnotationPresent(Transactional.class))
                .isTrue();
    }

    @Test
    void loginAttemptPolicyDoesNotExposeStringBusinessContracts() throws NoSuchMethodException {
        assertThat(LoginAttemptPolicy.class
                        .getDeclaredMethod("evaluate", String.class, String.class)
                        .getReturnType())
                .isEqualTo(LoginAttemptState.class);
    }

    @Test
    void passwordPolicyDoesNotExposeStringBusinessContracts() throws NoSuchMethodException {
        assertThat(UserPasswordPolicy.class
                        .getDeclaredMethod("validateOrThrow", String.class)
                        .getReturnType())
                .isEqualTo(Void.TYPE);
    }

    @Test
    void passwordResetIssuanceDoesNotExposeStringBusinessContracts() throws NoSuchMethodException {
        assertThat(PasswordResetChallengeService.class
                        .getDeclaredMethod("issueResetOtp", String.class, String.class, Boolean.TYPE)
                        .getReturnType())
                .isEqualTo(Void.TYPE);
        assertThat(PasswordResetChallengeService.class
                        .getDeclaredMethod(
                                "issueResetChallenge", String.class, String.class, String.class, Boolean.TYPE)
                        .getReturnType())
                .isEqualTo(Void.TYPE);
    }

    @Test
    void responseDtoConstructionStaysInAssemblerPackage() throws IOException {
        Path mainSourceRoot = Path.of("src/main/java/com/example/monkey");
        List<String> directResponseDtoConstructors = new ArrayList<>();
        try (Stream<Path> files = Files.walk(mainSourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().endsWith("Assembler.java"))
                    .forEach(path -> collectResponseDtoConstructors(path, directResponseDtoConstructors));
        }

        assertThat(directResponseDtoConstructors).isEmpty();
    }

    private static boolean isMappedEndpoint(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(RequestMapping.class);
    }

    private static boolean isExplicitStreamEndpoint(Method method) {
        if (Void.TYPE.equals(method.getReturnType())
                && hasParameterType(method, HttpServletResponse.class)
                && method.getName().endsWith("Captcha")) {
            return true;
        }
        if (!ResponseEntity.class.equals(method.getReturnType())
                || !(method.getGenericReturnType() instanceof ParameterizedType responseType)) {
            return false;
        }
        return Stream.of(responseType.getActualTypeArguments()).anyMatch(StreamingResponseBody.class::equals);
    }

    private static boolean hasParameterType(Method method, Class<?> expectedType) {
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (parameterType.equals(expectedType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnnotation(Annotation[] annotations, Class<? extends Annotation> expectedType) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType().equals(expectedType)) {
                return true;
            }
        }
        return false;
    }

    private static void assertValidatedModelAttribute(Method method, Class<?> requestType) {
        assertThat(method.getReturnType()).isEqualTo(Result.class);
        assertThat(method.getParameterTypes()[0]).isEqualTo(requestType);
        assertThat(hasAnnotation(method.getParameterAnnotations()[0], ModelAttribute.class))
                .isTrue();
        assertThat(hasAnnotation(method.getParameterAnnotations()[0], Valid.class))
                .isTrue();
    }

    private static void collectResponseDtoConstructors(Path path, List<String> findings) {
        try {
            String source = Files.readString(path);
            if (RESPONSE_DTO_CONSTRUCTOR.matcher(source).find()) {
                findings.add(path.toString());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect source file " + path, e);
        }
    }

    private static List<Class<?>> restControllers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(CONTROLLER_SCAN_ROOT).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(Objects::nonNull)
                .filter(ControllerContractBoundaryTest::isProductionControllerClass)
                .map(ControllerContractBoundaryTest::loadClass)
                .sorted(java.util.Comparator.comparing(Class::getName))
                .toList();
    }

    private static boolean isProductionControllerClass(String className) {
        Path sourceFile = Path.of("src/main/java", className.replace('.', '/') + ".java");
        return Files.isRegularFile(sourceFile);
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to load controller class " + className, e);
        }
    }
}

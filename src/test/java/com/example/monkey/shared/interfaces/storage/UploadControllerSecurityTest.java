package com.example.monkey.shared.interfaces.storage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.shared.application.observability.AuditService;
import com.example.monkey.shared.application.security.ApiRateLimitApplicationService;
import com.example.monkey.shared.application.security.ApiRateLimitResult;
import com.example.monkey.shared.application.storage.FileService;
import com.example.monkey.shared.application.storage.UploadFileContent;
import com.example.monkey.shared.application.storage.dto.UploadResponseDto;
import com.example.monkey.shared.application.tenant.PermissiveTenantAccessTestConfiguration;
import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import com.example.monkey.shared.infrastructure.config.SecurityConfig;
import com.example.monkey.shared.interfaces.web.VisitInterceptor;
import com.example.monkey.user.domain.UserAccountStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = UploadController.class)
@Import({SecurityConfig.class, PermissiveTenantAccessTestConfiguration.class})
@MockitoBean(
        types = {
            FileService.class,
            VisitInterceptor.class,
            UserAccountStore.class,
            AuditService.class,
            ApiRateLimitApplicationService.class,
            com.example.monkey.shared.domain.security.TrustedProxyPolicy.class
        })
class UploadControllerSecurityTest {

    private final MockMvc mockMvc;
    private final FileService fileService;
    private final ApiRateLimitApplicationService apiRateLimitService;

    @Autowired
    UploadControllerSecurityTest(
            MockMvc mockMvc, FileService fileService, ApiRateLimitApplicationService apiRateLimitService) {
        this.mockMvc = mockMvc;
        this.fileService = fileService;
        this.apiRateLimitService = apiRateLimitService;
    }

    @BeforeEach
    void allowRateLimitedTraffic() {
        when(apiRateLimitService.consume(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ApiRateLimitResult(true, 0));
    }

    @Test
    void userCanUploadAvatarThroughLegacyTypedEndpoint() throws Exception {
        when(fileService.uploadFile(any(UploadFileContent.class), eq("avatar")))
                .thenReturn(new UploadResponseDto("/images/avatar/alice.png", false));

        mockMvc.perform(multipart("/api/upload")
                        .file(imageFile())
                        .param("type", "avatar")
                        .with(csrf())
                        .with(user("alice").authorities(authorities("ROLE_USER", "UPLOAD_AVATAR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.path").value("/images/avatar/alice.png"))
                .andExpect(jsonPath("$.data.cropped").value(false));
    }

    @Test
    void userCannotUploadProductThroughLegacyTypedEndpoint() throws Exception {
        mockMvc.perform(multipart("/api/upload")
                        .file(imageFile())
                        .param("type", "product")
                        .with(csrf())
                        .with(user("alice").authorities(authorities("ROLE_USER", "UPLOAD_AVATAR"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value(ErrorCode.FORBIDDEN.defaultMessage()))
                .andExpect(jsonPath("$.instance").value("/api/upload"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        verify(fileService, never()).uploadFile(any(UploadFileContent.class), eq("product"));
    }

    @Test
    void adminCanUploadProductThroughLegacyTypedEndpoint() throws Exception {
        when(fileService.uploadFile(any(UploadFileContent.class), eq("product")))
                .thenReturn(new UploadResponseDto("/images/product/new.png", true));

        mockMvc.perform(multipart("/api/upload")
                        .file(imageFile())
                        .param("type", "product")
                        .with(csrf())
                        .with(user("admin").authorities(authorities("ROLE_ADMIN", "UPLOAD_PRODUCT_IMAGE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.path").value("/images/product/new.png"))
                .andExpect(jsonPath("$.data.cropped").value(true));
    }

    @Test
    void userCanUploadAvatarThroughDedicatedDtoEndpoint() throws Exception {
        when(fileService.uploadFile(any(UploadFileContent.class), eq("avatar")))
                .thenReturn(new UploadResponseDto("/images/avatar/new.png", false));

        mockMvc.perform(multipart("/api/upload/avatar")
                        .file(imageFile())
                        .with(csrf())
                        .with(user("alice").authorities(authorities("ROLE_USER", "UPLOAD_AVATAR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.path").value("/images/avatar/new.png"));
    }

    @Test
    void adminCanUploadProductThroughDedicatedDtoEndpoint() throws Exception {
        when(fileService.uploadFile(any(UploadFileContent.class), eq("product")))
                .thenReturn(new UploadResponseDto("/images/product/dedicated.png", true));

        mockMvc.perform(multipart("/api/upload/product")
                        .file(imageFile())
                        .with(csrf())
                        .with(user("admin").authorities(authorities("ROLE_ADMIN", "UPLOAD_PRODUCT_IMAGE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.path").value("/images/product/dedicated.png"));
    }

    @Test
    void unsupportedUploadTypeReturnsProblemDetail() throws Exception {
        when(fileService.uploadFile(any(UploadFileContent.class), eq("document")))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "unsupported upload type"));

        mockMvc.perform(multipart("/api/upload")
                        .file(imageFile())
                        .param("type", "document")
                        .with(csrf())
                        .with(user("admin").authorities(authorities("ROLE_ADMIN", "UPLOAD_PRODUCT_IMAGE"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("unsupported upload type"));

        verify(fileService).uploadFile(any(UploadFileContent.class), eq("document"));
    }

    @Test
    void directPresignedUploadEndpointsAreNotExposed() throws Exception {
        for (String endpoint : List.of("/api/upload/presigned", "/api/v1/uploads/presigned")) {
            mockMvc.perform(post(endpoint)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"avatar\",\"contentType\":\"image/png\"}")
                            .with(csrf())
                            .with(user("alice").authorities(authorities("ROLE_USER", "UPLOAD_AVATAR"))))
                    .andExpect(status().isNotFound());
        }

        verify(fileService, never()).createPresignedUpload(any(), any());
    }

    @Test
    void directPresignedGetEndpointsAreNotExposed() throws Exception {
        for (String endpoint : List.of("/api/upload/presigned-get", "/api/v1/uploads/presigned-get")) {
            mockMvc.perform(get(endpoint)
                            .param("objectKey", "avatar/alice.png")
                            .with(user("alice").authorities(authorities("ROLE_USER", "UPLOAD_AVATAR"))))
                    .andExpect(status().isNotFound());
        }

        verify(fileService, never()).createPresignedGetUrl(any());
    }

    private static MockMultipartFile imageFile() {
        return new MockMultipartFile("file", "image.png", "image/png", new byte[] {1, 2, 3});
    }

    private static List<SimpleGrantedAuthority> authorities(String... names) {
        return java.util.Arrays.stream(names).map(SimpleGrantedAuthority::new).toList();
    }
}

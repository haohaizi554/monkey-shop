package com.example.monkey.controller;

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

import com.example.monkey.config.SecurityConfig;
import com.example.monkey.domain.security.ApiRateLimiter;
import com.example.monkey.domain.storage.UploadFile;
import com.example.monkey.domain.user.UserAccountStore;
import com.example.monkey.dto.PresignedGetUrlResponseDto;
import com.example.monkey.dto.PresignedUploadResponseDto;
import com.example.monkey.dto.UploadResponseDto;
import com.example.monkey.service.AuditService;
import com.example.monkey.service.FileService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = UploadController.class)
@Import(SecurityConfig.class)
class UploadControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FileService fileService;

    @MockBean
    private com.example.monkey.interceptor.VisitInterceptor visitInterceptor;

    @MockBean
    private UserAccountStore userAccountStore;

    @MockBean
    private AuditService auditService;

    @MockBean
    private ApiRateLimiter apiRateLimitService;

    @BeforeEach
    void allowRateLimitedTraffic() {
        when(apiRateLimitService.consume(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ApiRateLimiter.RateLimitDecision(true, null, 0));
    }

    @Test
    void userCanUploadAvatarThroughLegacyTypedEndpoint() throws Exception {
        when(fileService.uploadFile(any(UploadFile.class), eq("avatar")))
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
                .andExpect(jsonPath("$.detail").value("Operation is not permitted"))
                .andExpect(jsonPath("$.instance").value("/api/upload"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        verify(fileService, never()).uploadFile(any(UploadFile.class), eq("product"));
    }

    @Test
    void adminCanUploadProductThroughLegacyTypedEndpoint() throws Exception {
        when(fileService.uploadFile(any(UploadFile.class), eq("product")))
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
        when(fileService.uploadFile(any(UploadFile.class), eq("avatar")))
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
        when(fileService.uploadFile(any(UploadFile.class), eq("product")))
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
        mockMvc.perform(multipart("/api/upload")
                        .file(imageFile())
                        .param("type", "document")
                        .with(csrf())
                        .with(user("admin").authorities(authorities("ROLE_ADMIN", "UPLOAD_PRODUCT_IMAGE"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value("unsupported upload type"));

        verify(fileService, never()).uploadFile(any(UploadFile.class), eq("document"));
    }

    @Test
    void userCanCreatePresignedAvatarUpload() throws Exception {
        when(fileService.createPresignedUpload("avatar", "image/png"))
                .thenReturn(new PresignedUploadResponseDto(
                        "avatar/alice.png",
                        "https://storage.example.test/upload",
                        Map.of("key", "avatar/alice.png"),
                        "https://cdn.example.test/avatar/alice.png",
                        Instant.parse("2026-01-01T00:15:00Z")));

        mockMvc.perform(post("/api/upload/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"avatar\",\"contentType\":\"image/png\"}")
                        .with(csrf())
                        .with(user("alice").authorities(authorities("ROLE_USER", "UPLOAD_AVATAR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.objectKey").value("avatar/alice.png"))
                .andExpect(jsonPath("$.data.uploadUrl").value("https://storage.example.test/upload"))
                .andExpect(jsonPath("$.data.formData.key").value("avatar/alice.png"))
                .andExpect(jsonPath("$.data.publicUrl").value("https://cdn.example.test/avatar/alice.png"));
    }

    @Test
    void userCannotCreateProductPresignedUpload() throws Exception {
        mockMvc.perform(post("/api/upload/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"product\",\"contentType\":\"image/png\"}")
                        .with(csrf())
                        .with(user("alice").authorities(authorities("ROLE_USER", "UPLOAD_AVATAR"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(fileService, never()).createPresignedUpload(eq("product"), eq("image/png"));
    }

    @Test
    void adminCanCreateProductPresignedUpload() throws Exception {
        when(fileService.createPresignedUpload("product", "image/jpeg"))
                .thenReturn(new PresignedUploadResponseDto(
                        "product/item.jpg",
                        "https://storage.example.test/upload",
                        Map.of("key", "product/item.jpg"),
                        "https://cdn.example.test/product/item.jpg",
                        Instant.parse("2026-01-01T00:15:00Z")));

        mockMvc.perform(post("/api/upload/presigned")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"product\",\"contentType\":\"image/jpeg\"}")
                        .with(csrf())
                        .with(user("admin").authorities(authorities("ROLE_ADMIN", "UPLOAD_PRODUCT_IMAGE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectKey").value("product/item.jpg"));
    }

    @Test
    void authenticatedUserCanCreatePresignedGetUrl() throws Exception {
        when(fileService.createPresignedGetUrl("product/item.png"))
                .thenReturn(new PresignedGetUrlResponseDto(
                        "product/item.png",
                        "https://storage.example.test/get/product/item.png",
                        Instant.parse("2026-01-01T00:15:00Z")));

        mockMvc.perform(get("/api/upload/presigned-get")
                        .param("objectKey", "product/item.png")
                        .with(user("alice").authorities(authorities("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.objectKey").value("product/item.png"))
                .andExpect(jsonPath("$.data.url").value("https://storage.example.test/get/product/item.png"));
    }

    private static MockMultipartFile imageFile() {
        return new MockMultipartFile("file", "image.png", "image/png", new byte[] {1, 2, 3});
    }

    private static List<SimpleGrantedAuthority> authorities(String... names) {
        return java.util.Arrays.stream(names).map(SimpleGrantedAuthority::new).toList();
    }
}

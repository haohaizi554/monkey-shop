package com.example.monkey.tenant.interfaces;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.tenant.application.TenantApplicationService;
import com.example.monkey.tenant.domain.TenantExportProvider;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TenantAdminControllerTest {

    @Test
    void completedExportIsDownloadedThroughAProtectedSameOriginStream() throws Exception {
        TenantApplicationService service = mock(TenantApplicationService.class);
        byte[] encryptedArchive = "encrypted-tenant-export".getBytes(StandardCharsets.UTF_8);
        when(service.downloadExportArtifact(200L, 2200L))
                .thenReturn(new TenantExportProvider.ExportArtifact(
                        new ByteArrayInputStream(encryptedArchive), encryptedArchive.length));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TenantAdminController(service))
                .build();

        MvcResult streamingResponse = mockMvc.perform(get("/api/v1/tenants/200/exports/2200/artifact"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(streamingResponse))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(
                        header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("tenant-200-export-2200.tink")))
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(content().bytes(encryptedArchive));
    }
}

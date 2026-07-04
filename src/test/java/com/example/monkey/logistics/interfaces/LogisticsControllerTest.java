package com.example.monkey.logistics.interfaces;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.monkey.logistics.application.LogisticsApplicationService;
import com.example.monkey.logistics.application.dto.FreightQuoteResponseDto;
import com.example.monkey.logistics.application.dto.LogisticsTrackingResponseDto;
import com.example.monkey.logistics.application.dto.ParsedAddressDto;
import com.example.monkey.logistics.domain.FreightChargeMode;
import com.example.monkey.logistics.domain.LogisticsCarrier;
import com.example.monkey.logistics.domain.TrackingStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LogisticsControllerTest {

    private LogisticsApplicationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(LogisticsApplicationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new LogisticsController(service))
                .build();
    }

    @Test
    void quoteParseAndWebhookDelegateToApplicationService() throws Exception {
        when(service.quoteFreight(any()))
                .thenReturn(new FreightQuoteResponseDto(
                        LogisticsCarrier.SF,
                        "Zhejiang",
                        new BigDecimal("1.20"),
                        2,
                        new BigDecimal("30.00"),
                        24,
                        List.of(FreightChargeMode.WEIGHT, FreightChargeMode.ITEM)));
        when(service.parseAddress(eq("Zhejiang Hangzou Xihu")))
                .thenReturn(new ParsedAddressDto("Zhejiang", "Hangzhou", "Xihu", "Zhejiang Hangzhou Xihu"));
        when(service.handleWebhook(any(), any())).thenReturn(tracking());

        mockMvc.perform(post("/api/logistics/freight/quote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"SF\",\"province\":\"Zhejiang\",\"weightKg\":1.2,\"itemCount\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(30.00));
        mockMvc.perform(post("/api/logistics/address/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Zhejiang Hangzou Xihu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.city").value("Hangzhou"));
        mockMvc.perform(
                        post("/api/logistics/webhook")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"carrier\":\"SF\",\"trackingNo\":\"SF7000\",\"eventId\":\"event-1\",\"event\":\"PICKUP\",\"signature\":\"valid-signature\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trackingNo").value("SF7000"));
    }

    private static LogisticsTrackingResponseDto tracking() {
        return new LogisticsTrackingResponseDto(
                7000L,
                "SF7000",
                10L,
                42L,
                LogisticsCarrier.SF,
                TrackingStatus.PICKED_UP,
                "Zhejiang",
                "Hangzhou",
                "Xihu",
                "Wenyi Road 100",
                new BigDecimal("30.00"),
                24,
                LocalDateTime.parse("2026-07-04T09:00:00"),
                null,
                null,
                null,
                LocalDateTime.parse("2026-07-04T08:00:00"),
                LocalDateTime.parse("2026-07-04T09:00:00"),
                List.of());
    }
}

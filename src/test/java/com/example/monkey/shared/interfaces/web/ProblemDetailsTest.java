package com.example.monkey.shared.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.monkey.shared.application.observability.TraceIds;
import com.example.monkey.shared.domain.exception.ErrorCode;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

class ProblemDetailsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void buildsRfcProblemDetailWithTypeInstanceCodeAndTraceId() {
        MDC.put(TraceIds.MDC_KEY, "trace-problem-1");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders/create");

        ProblemDetail problem = ProblemDetails.from(ErrorCode.CONFLICT, "duplicate order", request);

        assertThat(problem.getStatus())
                .isEqualTo(ErrorHttpStatuses.forCode(ErrorCode.CONFLICT).value());
        assertThat(problem.getTitle()).isEqualTo(ErrorCode.CONFLICT.defaultMessage());
        assertThat(problem.getDetail()).isEqualTo("duplicate order");
        assertThat(problem.getType()).isEqualTo(URI.create("https://monkeyshop.example/problems/CONFLICT"));
        assertThat(problem.getInstance()).isEqualTo(URI.create("/api/orders/create"));
        assertThat(problem.getProperties())
                .containsEntry("code", "CONFLICT")
                .containsEntry("traceId", "trace-problem-1");
    }
}

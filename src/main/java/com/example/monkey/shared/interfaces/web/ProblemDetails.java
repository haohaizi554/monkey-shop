package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.application.observability.TraceIds;
import com.example.monkey.shared.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

public final class ProblemDetails {

    private static final String PROBLEM_TYPE_BASE = "https://monkeyshop.example/problems/";

    private ProblemDetails() {}

    public static ProblemDetail from(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ErrorHttpStatuses.forCode(code), detail);
        problem.setTitle(code.defaultMessage());
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.code()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.code());
        problem.setProperty("traceId", TraceIds.currentOrCreate());
        return problem;
    }

    public static ResponseEntity<ProblemDetail> response(ErrorCode code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(ErrorHttpStatuses.forCode(code)).body(from(code, detail, request));
    }

    public static ResponseEntity<ProblemDetail> response(
            ErrorCode code, String detail, HttpServletRequest request, List<FieldViolation> fieldErrors) {
        ProblemDetail problem = from(code, detail, request);
        problem.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.status(ErrorHttpStatuses.forCode(code)).body(problem);
    }
}

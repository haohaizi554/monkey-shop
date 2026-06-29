package com.example.monkey.shared.web;

import com.example.monkey.shared.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

public final class ProblemDetails {

    private static final String PROBLEM_TYPE_BASE = "https://monkeyshop.example/problems/";

    private ProblemDetails() {}

    public static ProblemDetail from(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.httpStatus(), detail);
        problem.setTitle(code.defaultMessage());
        problem.setType(URI.create(PROBLEM_TYPE_BASE + code.code()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.code());
        problem.setProperty("traceId", TraceIds.currentOrCreate());
        return problem;
    }

    public static ResponseEntity<ProblemDetail> response(ErrorCode code, String detail, HttpServletRequest request) {
        return ResponseEntity.status(code.httpStatus()).body(from(code, detail, request));
    }
}

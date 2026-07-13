package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> handleBusinessException(BusinessException exception, HttpServletRequest request) {
        return problem(exception.errorCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(HttpServletRequest request) {
        return problem(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidationException(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldViolation> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::fieldViolation)
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return problem(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), request, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        List<FieldViolation> fieldErrors = exception.getConstraintViolations() == null
                ? List.of()
                : exception.getConstraintViolations().stream()
                        .map(violation -> new FieldViolation(
                                fieldName(violation.getPropertyPath().toString()),
                                violation
                                        .getConstraintDescriptor()
                                        .getAnnotation()
                                        .annotationType()
                                        .getSimpleName(),
                                violation.getMessage()))
                        .sorted(Comparator.comparing(FieldViolation::field))
                        .toList();
        return problem(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), request, fieldErrors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleBadRequest(HttpServletRequest request) {
        return problem(ErrorCode.REQUEST_MALFORMED, ErrorCode.REQUEST_MALFORMED.defaultMessage(), request);
    }

    private static FieldViolation fieldViolation(FieldError fieldError) {
        return new FieldViolation(fieldError.getField(), fieldError.getCode(), fieldErrorMessage(fieldError));
    }

    private static String fieldErrorMessage(FieldError fieldError) {
        String message = fieldError.getDefaultMessage();
        if (message == null || message.isBlank()) {
            message = ErrorCode.VALIDATION_FAILED.defaultMessage();
        }
        return message;
    }

    private static String fieldName(String propertyPath) {
        int lastSeparator = propertyPath.lastIndexOf('.');
        return lastSeparator < 0 ? propertyPath : propertyPath.substring(lastSeparator + 1);
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail, HttpServletRequest request) {
        return ProblemDetails.response(code, detail, request);
    }

    private static ResponseEntity<ProblemDetail> problem(
            ErrorCode code, String detail, HttpServletRequest request, List<FieldViolation> fieldErrors) {
        return ProblemDetails.response(code, detail, request, fieldErrors);
    }
}

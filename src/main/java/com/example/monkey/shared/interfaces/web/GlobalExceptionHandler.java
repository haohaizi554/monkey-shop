package com.example.monkey.shared.interfaces.web;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
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
        return problem(ErrorCode.VALIDATION_ERROR, validationDetail(exception), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(HttpServletRequest request) {
        return problem(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleBadRequest(HttpServletRequest request) {
        return problem(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(), request);
    }

    private static String validationDetail(MethodArgumentNotValidException exception) {
        return exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(GlobalExceptionHandler::fieldErrorDetail)
                .orElse(ErrorCode.VALIDATION_ERROR.defaultMessage());
    }

    private static String fieldErrorDetail(FieldError fieldError) {
        String message = fieldError.getDefaultMessage();
        if (message == null || message.isBlank()) {
            message = ErrorCode.VALIDATION_ERROR.defaultMessage();
        }
        return fieldError.getField() + ": " + message;
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail, HttpServletRequest request) {
        return ProblemDetails.response(code, detail, request);
    }
}

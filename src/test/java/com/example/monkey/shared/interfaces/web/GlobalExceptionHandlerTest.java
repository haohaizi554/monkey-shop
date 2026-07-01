package com.example.monkey.shared.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders/create");

    @Test
    void businessExceptionMapsItsErrorCodeAndMessage() {
        ResponseEntity<ProblemDetail> response =
                handler.handleBusinessException(new BusinessException(ErrorCode.CONFLICT, "duplicate order"), request);

        assertThat(response.getStatusCode()).isEqualTo(ErrorHttpStatuses.forCode(ErrorCode.CONFLICT));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("duplicate order");
        assertThat(response.getBody().getTitle()).isEqualTo(ErrorCode.CONFLICT.defaultMessage());
    }

    @Test
    void accessDeniedMapsToForbidden() {
        ResponseEntity<ProblemDetail> response =
                handler.handleAccessDenied(new AccessDeniedException("denied"), request);

        assertThat(response.getStatusCode()).isEqualTo(ErrorHttpStatuses.forCode(ErrorCode.FORBIDDEN));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo(ErrorCode.FORBIDDEN.defaultMessage());
    }

    @Test
    void validationExceptionUsesFirstFieldError() {
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "quantity", "must be positive"));
        when(exception.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ProblemDetail> response = handler.handleValidationException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(ErrorHttpStatuses.forCode(ErrorCode.VALIDATION_ERROR));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("quantity: must be positive");
    }

    @Test
    void validationExceptionFallsBackWhenFieldMessageIsBlank() {
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "quantity", " "));
        when(exception.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ProblemDetail> response = handler.handleValidationException(exception, request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail())
                .isEqualTo("quantity: " + ErrorCode.VALIDATION_ERROR.defaultMessage());
    }

    @Test
    void constraintViolationAndBadRequestUseValidationError() {
        ResponseEntity<ProblemDetail> constraintResponse =
                handler.handleConstraintViolation(new ConstraintViolationException("bad", java.util.Set.of()), request);
        ResponseEntity<ProblemDetail> badRequestResponse =
                handler.handleBadRequest(new RuntimeException("bad"), request);

        assertThat(constraintResponse.getStatusCode()).isEqualTo(ErrorHttpStatuses.forCode(ErrorCode.VALIDATION_ERROR));
        assertThat(constraintResponse.getBody()).isNotNull();
        assertThat(constraintResponse.getBody().getDetail()).isEqualTo(ErrorCode.VALIDATION_ERROR.defaultMessage());
        assertThat(badRequestResponse.getStatusCode()).isEqualTo(ErrorHttpStatuses.forCode(ErrorCode.VALIDATION_ERROR));
        assertThat(badRequestResponse.getBody()).isNotNull();
        assertThat(badRequestResponse.getBody().getDetail()).isEqualTo(ErrorCode.VALIDATION_ERROR.defaultMessage());
    }
}

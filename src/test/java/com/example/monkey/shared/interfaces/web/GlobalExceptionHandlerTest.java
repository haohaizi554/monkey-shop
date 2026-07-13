package com.example.monkey.shared.interfaces.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.monkey.shared.domain.exception.BusinessException;
import com.example.monkey.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

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
        ResponseEntity<ProblemDetail> response = handler.handleAccessDenied(request);

        assertThat(response.getStatusCode()).isEqualTo(ErrorHttpStatuses.forCode(ErrorCode.FORBIDDEN));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo(ErrorCode.FORBIDDEN.defaultMessage());
    }

    @Test
    void validationExceptionUsesSortedStructuredFieldErrors() {
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError(
                "request", "email", "email is invalid", false, new String[] {"Size"}, null, "email is invalid"));
        bindingResult.addError(new FieldError(
                "request",
                "username",
                "username is too long",
                false,
                new String[] {"Size"},
                null,
                "username is too long"));
        bindingResult.addError(new FieldError(
                "request",
                "username",
                "username is required",
                false,
                new String[] {"NotBlank"},
                null,
                "username is required"));
        when(exception.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ProblemDetail> response = handler.handleValidationException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(response.getBody().getProperties().get("fieldErrors").toString())
                .isEqualTo("[FieldViolation[field=email, code=Size, message=email is invalid], "
                        + "FieldViolation[field=username, code=NotBlank, message=username is required], "
                        + "FieldViolation[field=username, code=Size, message=username is too long]]");
    }

    @Test
    void validationExceptionUsesDefaultMessageWhenFieldMessageIsBlank() {
        MethodArgumentNotValidException exception = mock(MethodArgumentNotValidException.class);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "quantity", " "));
        when(exception.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ProblemDetail> response = handler.handleValidationException(exception, request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties().get("fieldErrors").toString())
                .contains(ErrorCode.VALIDATION_FAILED.defaultMessage());
    }

    @Test
    void constraintViolationAndBadRequestUseDistinctProblemCodes() {
        ResponseEntity<ProblemDetail> constraintResponse =
                handler.handleConstraintViolation(mock(jakarta.validation.ConstraintViolationException.class), request);
        ResponseEntity<ProblemDetail> badRequestResponse = handler.handleBadRequest(request);

        assertThat(constraintResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(constraintResponse.getBody()).isNotNull();
        assertThat(constraintResponse.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(badRequestResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRequestResponse.getBody()).isNotNull();
        assertThat(badRequestResponse.getBody().getProperties()).containsEntry("code", "REQUEST_MALFORMED");
    }

    @Test
    void returnValueValidationFailureRemainsAnInternalServerError() {
        HandlerMethodValidationException exception = mock(HandlerMethodValidationException.class);
        when(exception.isForReturnValue()).thenReturn(true);

        ResponseEntity<ProblemDetail> response = handler.handleMethodValidation(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }
}

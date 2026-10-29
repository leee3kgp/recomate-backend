package com.recomate.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> applicationError(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> invalidSessionRequest(InvalidRequestException exception) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", exception.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = status.value() == 400 ? "VALIDATION_ERROR" : "HTTP_" + status.value();
        return new ResponseEntity<>(ApiError.of(code, "요청을 확인해주세요."), headers, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpectedError(Exception exception) {
        // Log type only: SDK exception messages can contain user attributes or request data.
        LOG.error("User API operation failed: {}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(ApiError.of("INTERNAL_ERROR", "요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요."));
    }
}

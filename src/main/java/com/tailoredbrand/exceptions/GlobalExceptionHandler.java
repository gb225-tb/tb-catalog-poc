package com.tailoredbrand.exceptions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import com.google.api.gax.rpc.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /* -------------------- Pub/Sub Domain Errors -------------------- */

    @ExceptionHandler(PubSubPublishException.class)
    public ResponseEntity<ApiErrorResponse> handlePublishException(
            PubSubPublishException ex, HttpServletRequest request) {

        log.error("Pub/Sub publish error", ex);

        return buildError(
                "PUBSUB_PUBLISH_ERROR",
                ex.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                request,
                null
        );
    }

    @ExceptionHandler(PubSubConsumeException.class)
    public ResponseEntity<ApiErrorResponse> handleConsumeException(
            PubSubConsumeException ex, HttpServletRequest request) {

        log.error("Pub/Sub consume error", ex);

        return buildError(
                "PUBSUB_CONSUME_ERROR",
                ex.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                request,
                null
        );
    }

    /* -------------------- GCP / IAM / PubSub SDK Errors -------------------- */

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnauthenticated(
            UnauthenticatedException ex, HttpServletRequest request) {

        return buildError(
                "GCP_AUTHENTICATION_FAILED",
                "Invalid or missing GCP credentials",
                HttpStatus.UNAUTHORIZED,
                request,
                null
        );
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handlePermissionDenied(
            PermissionDeniedException ex, HttpServletRequest request) {

        return buildError(
                "GCP_PERMISSION_DENIED",
                "Service account lacks Pub/Sub permissions",
                HttpStatus.FORBIDDEN,
                request,
                null
        );
    }

    @ExceptionHandler(DeadlineExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleTimeout(
            DeadlineExceededException ex, HttpServletRequest request) {

        return buildError(
                "PUBSUB_TIMEOUT",
                "Pub/Sub request timed out",
                HttpStatus.GATEWAY_TIMEOUT,
                request,
                null
        );
    }

    @ExceptionHandler(ResourceExhaustedException.class)
    public ResponseEntity<ApiErrorResponse> handleQuotaExceeded(
            ResourceExhaustedException ex, HttpServletRequest request) {

        return buildError(
                "PUBSUB_QUOTA_EXCEEDED",
                "Pub/Sub quota exceeded",
                HttpStatus.TOO_MANY_REQUESTS,
                request,
                null
        );
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(
            NotFoundException ex, HttpServletRequest request) {

        return buildError(
                "PUBSUB_RESOURCE_NOT_FOUND",
                "Topic or subscription not found",
                HttpStatus.NOT_FOUND,
                request,
                null
        );
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleGcpInternalError(
            ApiException ex, HttpServletRequest request) {

        return buildError(
                "GCP_INTERNAL_ERROR",
                "Internal error from GCP Pub/Sub",
                HttpStatus.BAD_GATEWAY,
                request,
                null
        );
    }

    /* -------------------- Validation & Request Errors -------------------- */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<String> details = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.toList());

        return buildError(
                "VALIDATION_FAILED",
                "Invalid request payload",
                HttpStatus.BAD_REQUEST,
                request,
                details
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {

        List<String> details = ex.getConstraintViolations()
                .stream()
                .map(v -> v.getMessage())
                .collect(Collectors.toList());

        return buildError(
                "CONSTRAINT_VIOLATION",
                "Request parameter validation failed",
                HttpStatus.BAD_REQUEST,
                request,
                details
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedJson(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        return buildError(
                "MALFORMED_JSON",
                "Invalid or unreadable JSON request",
                HttpStatus.BAD_REQUEST,
                request,
                null
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParams(
            MissingServletRequestParameterException ex, HttpServletRequest request) {

        return buildError(
                "MISSING_PARAMETER",
                ex.getParameterName() + " is required",
                HttpStatus.BAD_REQUEST,
                request,
                null
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        return buildError(
                "METHOD_NOT_ALLOWED",
                ex.getMessage(),
                HttpStatus.METHOD_NOT_ALLOWED,
                request,
                null
        );
    }

    /* -------------------- Fallback -------------------- */

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnhandledException(
            Exception ex, HttpServletRequest request) {

        log.error("Unhandled exception occurred", ex);

        return buildError(
                "INTERNAL_SERVER_ERROR",
                "Unexpected error occurred",
                HttpStatus.INTERNAL_SERVER_ERROR,
                request,
                null
        );
    }

    /* -------------------- Helper -------------------- */

    private ResponseEntity<ApiErrorResponse> buildError(
            String code,
            String message,
            HttpStatus status,
            HttpServletRequest request,
            List<String> details) {

        ApiErrorResponse response = new ApiErrorResponse(
                code,
                message,
                LocalDateTime.now(),
                request.getRequestURI(),
                details
        );

        return ResponseEntity.status(status).body(response);
    }
}


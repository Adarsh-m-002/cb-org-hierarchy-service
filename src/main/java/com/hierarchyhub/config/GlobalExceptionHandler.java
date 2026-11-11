package com.hierarchyhub.config;

import com.hierarchyhub.dto.Constants;
import com.hierarchyhub.exception.ErrorCode;
import com.hierarchyhub.exception.InternalServerException;
import com.hierarchyhub.exception.ResourceNotFoundException;
import com.hierarchyhub.exception.ServiceException;
import com.hierarchyhub.model.ApiResponse;
import com.hierarchyhub.util.AppUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * App-wide exception handling: turns a thrown {@link ServiceException} (any feature, with its {@link ErrorCode}) or a
 * bean-validation failure into the standard {@link ApiResponse}, and keeps handling the older, unstructured
 * {@link ResourceNotFoundException}/{@link InternalServerException} the {@code /org/v1/hierarchy} code already throws,
 * plus a generic fallback for anything else.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ApiResponse> handleServiceException(ServiceException ex, WebRequest request) {
        if (ex.errorCode().httpStatus().is5xxServerError()) {
            log.error("Request failed [{}] on {}: {}", ex.errorCode(), request.getDescription(false), ex.getMessage(), ex);
        } else {
            log.info("Request rejected [{}] on {}: {}", ex.errorCode(), request.getDescription(false), ex.getMessage());
        }
        ApiResponse response = failureResponse(request, ex.errorCode().httpStatus(), ex.errorCode().name(), ex.getMessage());
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleBeanValidation(MethodArgumentNotValidException ex, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.info("Request rejected [{}] on {}: {}", ErrorCode.INVALID_REQUEST, request.getDescription(false), message);
        ApiResponse response = failureResponse(request, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST.name(), message);
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ApiResponse> handleMissingHeader(ServletRequestBindingException ex, WebRequest request) {
        boolean missingAuthToken = ex.getMessage() != null && ex.getMessage().contains(Constants.AUTH_TOKEN_HEADER);
        log.info("Request rejected on {}: {}", request.getDescription(false), ex.getMessage());
        ApiResponse response = missingAuthToken
                ? failureResponse(request, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.name(),
                        "An access token (" + Constants.AUTH_TOKEN_HEADER + " header) is required for this API")
                : failureResponse(request, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST.name(), ex.getMessage());
        return new ResponseEntity<>(response, response.getResponseCode());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFoundException(ResourceNotFoundException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("message", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(InternalServerException.class)
    public ResponseEntity<Map<String, String>> handleInternalServerException(InternalServerException ex) {
        Map<String, String> error = new HashMap<>();
        error.put("message", "An unexpected error occurred");
        error.put("errMessage", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGlobalException(Exception ex) {
        Map<String, String> error = new HashMap<>();
        error.put("message", "An unexpected error occurred");
        error.put("errMessage", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /** Builds the failure envelope from the request's own path: the api id, and its {@code vN} segment as the version (falls back to ApiResponse's "v1" default when the path has none). */
    private static ApiResponse failureResponse(WebRequest request, HttpStatus status, String errorCode, String message) {
        String uri = request.getDescription(false).replaceFirst("^uri=", "");
        String apiId = "api" + uri.replace('/', '.');
        ApiResponse response = ApiResponse.failure(apiId, status, errorCode, message);
        String version = AppUtil.apiVersionFromUri(uri);
        if (version != null) {
            response.setVer(version);
        }
        return response;
    }
}

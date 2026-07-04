package com.eventbooking.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> authentication(AuthenticationException ex, HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "Invalid email or password", request, Map.of());
    }

    /**
     * Thrown manually from services/controllers when the caller is
     * authenticated but not allowed to act on a resource they don't own
     * (e.g. viewing/cancelling someone else's booking). This is distinct
     * from Spring Security's own AccessDeniedHandler bean in SecurityConfig,
     * which only fires for hasRole(...)-style authorization decisions made
     * by the AuthorizationFilter — exceptions thrown inside a controller/
     * service are resolved here, inside the DispatcherServlet, before they
     * would ever reach that filter-level handler. Without this handler they
     * fell through to the generic Exception.class handler below as a 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> accessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler({DuplicateResourceException.class, DuplicateBookingException.class,})
    ResponseEntity<ApiError> conflict(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT,ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(SeatsUnavailableException.class)
    ResponseEntity<ApiError> seatsUnavailable(SeatsUnavailableException ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> illegalState(IllegalStateException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request, Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "Validation failed", request, errors);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> dataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String message = ex.getMostSpecificCause().getMessage();
        if (message != null && message.toLowerCase().contains("duplicate")) {
            return response(HttpStatus.CONFLICT, "A record with this value already exists", request, Map.of());
        }
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "A database error occurred", request, Map.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraint(ConstraintViolationException ex, HttpServletRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(error ->
                errors.put(error.getPropertyPath().toString(), error.getMessage()));
        return response(HttpStatus.BAD_REQUEST, "Validation failed", request, errors);
    }

    @ExceptionHandler({CannotAcquireLockException.class, ObjectOptimisticLockingFailureException.class,
            LockAcquisitionException.class})
    ResponseEntity<ApiError> locking(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "The resource is busy; retry the request", request, Map.of());
    }

    /**
     * Catch-all for anything not handled above — bugs, NPEs, unexpected
     * runtime failures. Without this, such exceptions fell through to Spring
     * Boot's default error page/JSON, which has a different shape than every
     * other error response from this API and (depending on config) can leak
     * exception class names or stack traces to the client.
     *
     * Full details are logged server-side for debugging; the client only
     * ever sees a generic message.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.", request, Map.of());
    }


    private ResponseEntity<ApiError> response(HttpStatus status, String message,
                                              HttpServletRequest request, Map<String, String> errors) {
        return ResponseEntity.status(status).body(new ApiError(
                LocalDateTime.now(), status.value(), status.getReasonPhrase(), message,
                request.getRequestURI(), errors));
    }

    public record ApiError(LocalDateTime timestamp, int status, String error, String message,
                           String path, Map<String, String> validationErrors) {
    }
}
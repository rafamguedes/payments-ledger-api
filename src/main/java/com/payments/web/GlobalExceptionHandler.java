package com.payments.web;

import com.payments.domain.ApiExceptions.ConflictException;
import com.payments.domain.ApiExceptions.NotFoundException;
import com.payments.domain.ApiExceptions.UnprocessableEntityException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnprocessableEntityException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleUnprocessable(UnprocessableEntityException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleConflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, "conflict", e.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleNotFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    /**
     * Malformed JSON, missing body, or a field with the wrong type — e.g.
     * amount: 25.5 where a Long is expected — all land here. All of these
     * are payload-shape problems, so they map to 422 per the spec.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Dtos.ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_request", "malformed or invalid request body");
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, QueryTimeoutException.class})
    public ResponseEntity<Dtos.ErrorResponse> handleDatabaseUnavailable(Exception e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable", "database capacity temporarily exhausted");
    }

    private ResponseEntity<Dtos.ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new Dtos.ErrorResponse(code, message));
    }
}

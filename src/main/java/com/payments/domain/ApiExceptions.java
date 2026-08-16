package com.payments.domain;

public class ApiExceptions {

    /** Maps to 422 — malformed / semantically invalid payload. */
    public static class UnprocessableEntityException extends RuntimeException {
        public UnprocessableEntityException(String message) {
            super(message);
        }
    }

    /** Maps to 409 — resource already exists. */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** Maps to 404 — resource not found. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    private ApiExceptions() {
    }
}

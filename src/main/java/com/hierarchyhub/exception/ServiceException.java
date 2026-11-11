package com.hierarchyhub.exception;

/**
 * A business rule was broken (bad input, a missing record, a conflict, ...). Carries a stable {@link ErrorCode} that
 * {@link com.hierarchyhub.config.GlobalExceptionHandler} turns into the right HTTP status and error body - not tied to
 * any one feature, any part of the app can throw this with whichever {@link ErrorCode} applies.
 */
public class ServiceException extends RuntimeException {

    private final ErrorCode errorCode;

    public ServiceException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ServiceException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}

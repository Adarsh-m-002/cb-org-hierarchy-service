package com.hierarchyhub.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, app-wide error codes, each with the HTTP status it is reported as. Not scoped to one feature: the
 * org-hierarchy v2 APIs (link/move/unlink/read/hierarchy) use the entries below today, but any new feature can add its
 * own entries here and throw {@link ServiceException} with them rather than introducing a parallel error-code type.
 */
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    ORG_NOT_FOUND(HttpStatus.NOT_FOUND),
    ORG_INACTIVE(HttpStatus.BAD_REQUEST),
    ALREADY_HAS_PARENT(HttpStatus.CONFLICT),
    IS_AN_L0(HttpStatus.CONFLICT),
    NOT_LINKED(HttpStatus.CONFLICT),
    ORGANISATION_HAS_CHILDREN(HttpStatus.BAD_REQUEST),
    CYCLE_DETECTED(HttpStatus.CONFLICT),
    MINISTRY_MISMATCH(HttpStatus.CONFLICT),
    HIERARCHY_TYPE_MISMATCH(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    DEPENDENCY_FAILURE(HttpStatus.SERVICE_UNAVAILABLE),
    FEATURE_DISABLED(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}

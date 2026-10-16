package com.pa.sagememory.agent;

import org.springframework.http.HttpStatus;

/** Public, credential-free error details for the Add pipeline. */
public class AddAgentException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AddAgentException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public AddAgentException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}

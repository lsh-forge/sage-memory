package com.pa.sagememory.agent;

import org.springframework.http.HttpStatus;

/** Search 对外安全错误；内部原因链供日志脱敏后排查，不向接口暴露模型异常或认证信息。 */
public class SearchAgentException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public SearchAgentException(HttpStatus status, String code, String message) { this(status, code, message, null); }
    public SearchAgentException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause); this.status = status; this.code = code;
    }
    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}

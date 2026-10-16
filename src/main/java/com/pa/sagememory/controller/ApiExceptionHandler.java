package com.pa.sagememory.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pa.sagememory.agent.AddAgentException;
import com.pa.sagememory.agent.SearchAgentException;
import com.pa.sagememory.agent.AddLogSupport;
import com.pa.sagememory.agent.middleware.AgentLogRedactor;
import com.pa.sagememory.service.MemoryPipelineNotImplementedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final AgentLogRedactor redactor;

    @Autowired
    public ApiExceptionHandler(AgentLogRedactor redactor) { this.redactor = redactor; }

    /** 手动构造的离线接口验收同样使用脱敏日志，不依赖 Spring 容器。 */
    public ApiExceptionHandler() { this(new AgentLogRedactor()); }

    @ExceptionHandler(SearchAgentException.class)
    public ResponseEntity<ApiError> searchFailed(SearchAgentException error) {
        log.warn("[Search接口错误响应] HTTP状态={}，错误码={}；以下为完整脱敏异常，接口不返回内部原因链\n{}",
                error.getStatus().value(), error.getCode(), redactor.exception(error));
        return ResponseEntity.status(error.getStatus()).body(new ApiError(error.getCode(), error.getMessage()));
    }

    @ExceptionHandler(AddAgentException.class)
    public ResponseEntity<ApiError> addFailed(AddAgentException error) {
        log.warn("[接口错误响应] HTTP状态={}，{}；以下为完整脱敏异常，接口不返回内部原因链\n{}",
                error.getStatus().value(), AddLogSupport.failure(error), redactor.exception(error));
        return ResponseEntity.status(error.getStatus())
                .body(new ApiError(error.getCode(), error.getMessage()));
    }

    @ExceptionHandler(MemoryPipelineNotImplementedException.class)
    public ResponseEntity<ApiError> pipelineNotImplemented(MemoryPipelineNotImplementedException error) {
        log.warn("[接口功能未实现] HTTP状态=501，错误码=MEMORY_PIPELINE_NOT_IMPLEMENTED");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(new ApiError("MEMORY_PIPELINE_NOT_IMPLEMENTED", error.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> invalidRequest(ResponseStatusException error) {
        log.warn("[接口参数校验失败] HTTP状态={}，错误码=INVALID_REQUEST；请检查角色、标识及 content 结构", error.getStatusCode().value());
        return ResponseEntity.status(error.getStatusCode())
                .body(new ApiError("INVALID_REQUEST", error.getReason()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformedRequest(HttpMessageNotReadableException error) {
        // Jackson 异常可能包含请求原文，所以不直接打印 error 或其 message。
        log.warn("[接口 JSON 解析失败] HTTP状态=400，错误码=INVALID_JSON；请求体为空或 JSON 格式不合法");
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_JSON", "Request body is missing or malformed."));
    }

    public record ApiError(@JsonProperty("error_code") String errorCode, String message) {
    }
}

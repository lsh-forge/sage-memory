package com.pa.sagememory.controller;

import com.pa.sagememory.service.AddService;
import com.pa.sagememory.agent.AddAgentProperties;
import com.pa.sagememory.agent.SearchAgentProperties;
import com.pa.sagememory.agent.SearchAgentException;
import com.pa.sagememory.agent.AddAgentException;
import com.pa.sagememory.agent.AddLogSupport;
import com.pa.sagememory.service.SearchService;
import com.pa.sagememory.dto.AddRequest;
import com.pa.sagememory.dto.AddResponse;
import com.pa.sagememory.dto.SearchRequest;
import com.pa.sagememory.dto.SearchResponse;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class ApiController {
    private static final Logger log = LoggerFactory.getLogger(ApiController.class);
    private final AddService addService;
    private final SearchService searchService;
    private final ApiRequestValidator validator;
    private final AddAgentProperties properties;
    private final SearchAgentProperties searchProperties;

    public ApiController(AddService addService, SearchService searchService, ApiRequestValidator validator,
            AddAgentProperties properties, SearchAgentProperties searchProperties) {
        this.addService = addService;
        this.searchService = searchService;
        this.validator = validator;
        this.properties = properties;
        this.searchProperties = searchProperties;
    }

    @PostMapping(path = "/add", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<AddResponse> add(@RequestBody AddRequest request) {
        validator.validate(request);
        String trace = AddLogSupport.trace(request);
        long startedAt = System.nanoTime();
        log.info("[Add接口接收] {}，消息数={}，HTTP等待上限={}秒", trace,
                request.messages().size(), properties.getHttpWaitTimeoutSeconds());
        var job = addService.addAsync(request);
        DeferredResult<AddResponse> response = new DeferredResult<>(properties.getHttpWaitTimeoutSeconds() * 1000L);
        response.onTimeout(() -> {
            log.warn("[Add接口等待超时] {}，等待耗时={}ms；后台任务不取消，请使用原 request_id 重试", trace,
                    AddLogSupport.elapsedMillis(startedAt));
            response.setErrorResult(new AddAgentException(HttpStatus.GATEWAY_TIMEOUT,
                    "ADD_HTTP_WAIT_TIMEOUT", "HTTP wait expired. Retry with the same request_id; do not submit a new request_id."));
        });
        response.onError(error -> log.warn("[Add接口连接异常] {}，{}；不取消后台共享任务", trace, AddLogSupport.failure(error)));
        // HTTP 连接断开或等待超时不能取消共享任务，避免重试与尚未结束的写入重叠。
        job.whenComplete((result, error) -> {
            if (error == null) {
                boolean accepted = response.setResult(result);
                log.info("[Add接口结果] {}，任务完成，响应已接纳={}，等待耗时={}ms", trace, accepted,
                        AddLogSupport.elapsedMillis(startedAt));
            }
            else {
                Throwable cause = error;
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                boolean accepted = response.setErrorResult(cause);
                log.warn("[Add接口结果] {}，任务失败，错误响应已接纳={}，{}，等待耗时={}ms", trace,
                        accepted, AddLogSupport.failure(cause), AddLogSupport.elapsedMillis(startedAt));
            }
        });
        return response;
    }

    @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DeferredResult<SearchResponse> search(@RequestBody SearchRequest request) {
        validator.validate(request);
        log.info("[Search接口接收] user_id={}，top_k={}；不打印查询正文", AddLogSupport.value(request.userId()), request.topK());
        long started = System.nanoTime();
        var job = searchService.searchAsync(request);
        DeferredResult<SearchResponse> response = new DeferredResult<>(searchProperties.getHttpWaitTimeoutSeconds() * 1000L);
        response.onTimeout(() -> response.setErrorResult(new SearchAgentException(HttpStatus.GATEWAY_TIMEOUT,
                "SEARCH_HTTP_WAIT_TIMEOUT", "Search HTTP wait expired.")));
        job.whenComplete((result, error) -> {
            if (error == null) {
                response.setResult(result);
                log.info("[Search接口完成] user_id={}，证据数={}，耗时={}ms", AddLogSupport.value(request.userId()), result.data().size(), AddLogSupport.elapsedMillis(started));
            } else {
                Throwable cause = error;
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                response.setErrorResult(cause);
            }
        });
        return response;
    }
}

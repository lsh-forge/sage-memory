package com.pa.sagememory.service;

import com.pa.sagememory.dto.SearchRequest;
import com.pa.sagememory.dto.SearchResponse;
import java.util.concurrent.CompletableFuture;

/** 检索同步入口及 HTTP 异步适配；只返回模型选择的证据。 */
public interface SearchService {
    public default SearchResponse search(SearchRequest request) {
        throw new MemoryPipelineNotImplementedException("Search");
    }
    default CompletableFuture<SearchResponse> searchAsync(SearchRequest request) { return CompletableFuture.completedFuture(search(request)); }
}

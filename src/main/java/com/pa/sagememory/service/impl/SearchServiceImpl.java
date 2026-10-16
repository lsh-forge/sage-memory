package com.pa.sagememory.service.impl;

import com.pa.sagememory.dto.SearchRequest;
import com.pa.sagememory.dto.SearchResponse;
import com.pa.sagememory.service.SearchService;
import com.pa.sagememory.agent.SearchAgentService;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

/**
 * @author : lsh
 * @Description : 将 Search 接口交给检索 Agent，返回证据而非问题答案。
 * @data : 2026/10/12 10:57
 */
@Service
public class SearchServiceImpl implements SearchService {
    private final SearchAgentService agent;
    public SearchServiceImpl(SearchAgentService agent) { this.agent = agent; }
    @Override
    public SearchResponse search(SearchRequest request) {
        return agent.search(request);
    }
    @Override public CompletableFuture<SearchResponse> searchAsync(SearchRequest request) { return agent.searchAsync(request); }
}

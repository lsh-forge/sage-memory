package com.pa.sagememory.service.impl;

import com.pa.sagememory.dto.AddRequest;
import com.pa.sagememory.dto.AddResponse;
import com.pa.sagememory.agent.AddAgentService;
import com.pa.sagememory.service.AddService;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

/**
 * @author : lsh
 * @Description : Add 接口服务入口，统一委托给批量记忆治理 Agent。
 * @data : 2026/10/12 10:56
 */
@Service
public class AddServiceImpl implements AddService {
    private final AddAgentService addAgentService;

    public AddServiceImpl(AddAgentService addAgentService) {
        this.addAgentService = addAgentService;
    }

    @Override
    public AddResponse add(AddRequest request) {
        return addAgentService.add(request);
    }

    @Override
    public CompletableFuture<AddResponse> addAsync(AddRequest request) {
        // 不在此创建线程或提前返回成功；任务排队、去重和超时由 Agent 执行器管理。
        return addAgentService.addAsync(request);
    }
}

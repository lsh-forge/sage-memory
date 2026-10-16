package com.pa.sagememory.service;

import com.pa.sagememory.dto.AddRequest;
import com.pa.sagememory.dto.AddResponse;
import java.util.concurrent.CompletableFuture;

/** Entry point for future ReAct tools and Harness-inspired Markdown memory governance. */
public interface AddService {
    default CompletableFuture<AddResponse> addAsync(AddRequest request) {
        try { return CompletableFuture.completedFuture(add(request)); }
        catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
    }

    public default AddResponse add(AddRequest request) {
        // TODO: Persist ordered source messages/media and govern Markdown memory through tools.
        // Do not acknowledge success before the batch is durable and searchable.
        throw new MemoryPipelineNotImplementedException("Add");
    }
}

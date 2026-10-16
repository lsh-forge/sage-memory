package com.pa.sagememory.service;

public class MemoryPipelineNotImplementedException extends RuntimeException {
    public MemoryPipelineNotImplementedException(String operation) {
        super(operation + " memory pipeline is not implemented yet.");
    }
}

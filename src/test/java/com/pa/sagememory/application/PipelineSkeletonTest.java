package com.pa.sagememory.application;

import com.pa.sagememory.service.AddService;
import com.pa.sagememory.service.MemoryPipelineNotImplementedException;
import com.pa.sagememory.service.SearchService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineSkeletonTest {

    @Test
    void coreReActAndToolkitAreAvailableWithoutModelConfiguration() {
        assertThat(ReActAgent.builder()).isNotNull();
        assertThat(new Toolkit()).isNotNull();
    }
}

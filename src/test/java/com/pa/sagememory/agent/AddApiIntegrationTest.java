package com.pa.sagememory.agent;

import com.pa.sagememory.agent.workspace.AddWorkspaceStore;
import io.agentscope.core.model.Model;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "sage.memory.api.api-key=00000000-0000-4000-8000-000000000001")
@AutoConfigureMockMvc
@Import(AddApiIntegrationTest.OfflineModel.class)
@TestExecutionListeners(listeners = {ServletTestExecutionListener.class,
        DependencyInjectionTestExecutionListener.class, DirtiesContextTestExecutionListener.class},
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class AddApiIntegrationTest {
    private static MockHttpServletRequestBuilder post(String path) {
        return MockMvcRequestBuilders.post(path).header("X-Api-Key", "00000000-0000-4000-8000-000000000001");
    }
    private static final Path WORKSPACE = Path.of("target", "add-api-" + UUID.randomUUID()).toAbsolutePath();
    @Autowired MockMvc mvc;
    @Autowired AddWorkspaceStore store;
    @Autowired Model model;

    @DynamicPropertySource
    static void workspace(DynamicPropertyRegistry registry) {
        registry.add("sage.memory.add.workspace-root", WORKSPACE::toString);
        registry.add("sage.memory.add.api-key", () -> "");
    }

    @Test
    void realAddEndpointRunsTheServiceAndReActToolsBeforeReturningSuccess() throws Exception {
        String request = """
                {"request_id":"api-r1","user_id":"api-user","session_id":"api-session","messages":[
                {"role":"user","timestamp":1740873600000,"content":"one"},
                {"role":"user","content":"two"}, {"role":"user","content":"three"},
                {"role":"user","content":"four"}, {"role":"user","content":"five"},
                {"role":"user","content":"six"}]}
                """;
        performAdd(request)
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.request_id").value("api-r1"))
                .andExpect(jsonPath("$.user_id").value("api-user"))
                .andExpect(jsonPath("$.session_id").value("api-session"));
        var offline = (AddAgentServiceTest.SavingModel) model;
        assertThat(offline.batches).hasSize(2);
        assertThat(Files.readString(store.userRoot("api-user").resolve("MEMORY.md")))
                .contains("batch-0 fact", "batch-1 fact");
        performAdd(request)
                .andExpect(status().isOk());
        assertThat(offline.batches).hasSize(2);

        mvc.perform(post("/add").contentType(MediaType.APPLICATION_JSON).content("""
                {"request_id":"image-r1","user_id":"image-user","session_id":"s","messages":[
                {"role":"user","content":[{"type":"image_url",
                "image_url":{"url":"data:image/png;base64,AA=="}}]}]}
                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error_code").value("ADD_INVALID_IMAGE"));
        assertThat(store.userRoot("image-user")).doesNotExist();

        performAdd("""
                {"request_id":"image-r2","user_id":"image-user","session_id":"s","messages":[
                {"role":"user","timestamp":1740873600000,"content":[{"type":"text","text":"这是 Milo 的照片"},
                {"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6AAAAAElFTkSuQmCC"}}]}]}
                """)
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertThat(offline.batches.get(2).path("new_messages").get(0).path("content").asString())
                .contains("User message: 这是 Milo 的照片", "[Attached image 1 description]: ", "蓝色胸背带");
        assertThat(Files.readString(store.userRoot("image-user").resolve("agents/add-memory/sessions/s.jsonl")))
                .contains("image_id", "p1-1", "original_content", "蓝色胸背带");
    }

    private ResultActions performAdd(String json) throws Exception {
        var action = mvc.perform(post("/add").contentType(MediaType.APPLICATION_JSON).content(json));
        var result = action.andReturn();
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
        result.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(result));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OfflineModel {
        @Bean @Primary Model model() { return new AddAgentServiceTest.SavingModel(); }
        @Bean("visionModel") Model visionModel(Model model) { return model; }
    }
}

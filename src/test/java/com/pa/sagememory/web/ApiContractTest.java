package com.pa.sagememory.web;

import com.pa.sagememory.service.AddService;
import com.pa.sagememory.service.SearchService;
import com.pa.sagememory.dto.AddRequest;
import com.pa.sagememory.dto.AddResponse;
import com.pa.sagememory.dto.EvidenceCard;
import com.pa.sagememory.dto.SearchRequest;
import com.pa.sagememory.dto.SearchResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.pa.sagememory.agent.AddAgentException;
import jakarta.servlet.AsyncEvent;
import org.springframework.mock.web.MockAsyncContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "sage.memory.api.api-key=00000000-0000-4000-8000-000000000001")
@AutoConfigureMockMvc
@Import(ApiContractTest.TestServices.class)
// These contract tests use explicit service doubles, not Mockito or JVM instrumentation.
@TestExecutionListeners(
        listeners = {ServletTestExecutionListener.class, DependencyInjectionTestExecutionListener.class,
                DirtiesContextTestExecutionListener.class},
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class ApiContractTest {
    private static final String TEST_API_KEY = "00000000-0000-4000-8000-000000000001";

    private static MockHttpServletRequestBuilder post(String path) {
        return MockMvcRequestBuilders.post(path).header("X-Api-Key", TEST_API_KEY);
    }
    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestAddService addService;

    @Autowired
    private TestSearchService searchService;

    @BeforeEach
    void unconfiguredPipeline() {
        addService.respond = false;
        addService.lastRequest = null;
        addService.pending = null;
        searchService.respond = false;
        searchService.lastRequest = null;
    }

    @Test
    void healthIsAnUnauthenticatedGetWithoutAddOrSearchWork() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/health").header("Authorization", "Bearer not-required"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/health").header("X-Api-Key", "wrong-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        assertThat(addService.lastRequest).isNull();
        assertThat(searchService.lastRequest).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"/add", "/search"})
    void suppliedInvalidCredentialsAreRejectedBeforeParsingOrServiceCalls(String path) throws Exception {
        for (String supplied : List.of("", "wrong-key", " " + TEST_API_KEY, TEST_API_KEY + " ")) {
            mvc.perform(MockMvcRequestBuilders.post(path).header("X-Api-Key", supplied)
                    .contentType(MediaType.APPLICATION_JSON).content("malformed-json"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error_code").value("UNAUTHORIZED"));
        }
        mvc.perform(MockMvcRequestBuilders.post(path).header("X-Api-Key", TEST_API_KEY, "wrong-key")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        assertThat(addService.lastRequest).isNull();
        assertThat(searchService.lastRequest).isNull();
    }

    @Test
    void addAndSearchWithoutApiKeyReachTheServices() throws Exception {
        addService.respond = true;
        searchService.respond = true;
        perform(MockMvcRequestBuilders.post("/add").contentType(MediaType.APPLICATION_JSON).content(validAdd()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        perform(MockMvcRequestBuilders.post("/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"user-1\",\"query\":\"Find related history\",\"top_k\":100}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("event-1"));
        assertThat(addService.lastRequest).isNotNull();
        assertThat(searchService.lastRequest).isNotNull();
    }

    @Test
    void apiKeyHeaderNamesAreCaseInsensitive() throws Exception {
        addService.respond = true;
        perform(MockMvcRequestBuilders.post("/add").header("x-api-key", TEST_API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(validAdd()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void textAddPreservesBothRolesTimestampsAndEchoIdentifiers() throws Exception {
        addService.respond = true;

        perform(post("/add").contentType(MediaType.APPLICATION_JSON).content("""
                {
                  "request_id":"request-1", "user_id":"user-1", "session_id":"session-1",
                  "messages":[
                    {"role":"assistant","timestamp":1740873600000,"content":"Which dog?"},
                    {"role":"user","timestamp":1740873600000,"content":"  The second one.  "}
                  ]
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.request_id").value("request-1"))
                .andExpect(jsonPath("$.user_id").value("user-1"))
                .andExpect(jsonPath("$.session_id").value("session-1"));

        assertThat(addService.lastRequest).isNotNull();
        assertThat(addService.lastRequest.messages()).extracting(message -> message.role())
                .containsExactly("assistant", "user");
        assertThat(addService.lastRequest.messages().get(1).timestamp()).isEqualTo(1740873600000L);
        assertThat(addService.lastRequest.messages().get(1).content().asString())
                .isEqualTo("  The second one.  ");
    }

    @Test
    void multimodalAddPreservesContentBlockOrderAndOptionalTimestamp() throws Exception {
        perform(post("/add").contentType(MediaType.APPLICATION_JSON).content("""
                {
                  "request_id":"request-2", "user_id":"user-1", "session_id":"session-1",
                  "messages":[{"role":"user","content":[
                    {"type":"text","text":"Before the image"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}},
                    {"type":"text","text":"After the image"}
                  ]}]
                }
                """))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error_code").value("MEMORY_PIPELINE_NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.success").doesNotExist());

        assertThat(addService.lastRequest).isNotNull();
        var message = addService.lastRequest.messages().get(0);
        assertThat(message.timestamp()).isNull();
        assertThat(message.content().get(0).get("text").asString()).isEqualTo("Before the image");
        assertThat(message.content().get(1).get("type").asString()).isEqualTo("image_url");
        assertThat(message.content().get(2).get("text").asString()).isEqualTo("After the image");
    }

    @Test
    void multimodalSearchPreservesOptionsAndReturnsEvidenceContent() throws Exception {
        searchService.respond = true;

        perform(post("/search").contentType(MediaType.APPLICATION_JSON).content("""
                {
                  "user_id":"user-1", "top_k":100, "options":["A. Milo","B. Rocky"],
                  "query":[
                    {"type":"text","text":"Which dog is this?"},
                    {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AA=="}}
                  ]
                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("event-1"))
                .andExpect(jsonPath("$.data[0].content[0].type").value("text"))
                .andExpect(jsonPath("$.data[0].content[1].type").value("image_url"))
                .andExpect(jsonPath("$.data[0].score").doesNotExist())
                .andExpect(jsonPath("$.answer").doesNotExist());

        assertThat(searchService.lastRequest).isNotNull();
        assertThat(searchService.lastRequest.options()).containsExactly("A. Milo", "B. Rocky");
        assertThat(searchService.lastRequest.topK()).isEqualTo(100);
    }

    @Test
    void unconfiguredTextSearchDoesNotPretendToHaveRun() throws Exception {
        mvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content("""
                {"user_id":"user-1","query":"Milo","top_k":100}
                """))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error_code").value("MEMORY_PIPELINE_NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "[]", "{}", "123", "[{\"type\":\"audio\"}]",
            "[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/image.png\"}}]"})
    void invalidContentDoesNotReachIngestion(String content) throws Exception {
        String request = """
                {"request_id":"request-1","user_id":"user-1","session_id":"session-1",
                 "messages":[{"role":"user","content":%s}]}
                """.formatted(content);
        mvc.perform(post("/add").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"));
        assertThat(addService.lastRequest).isNull();
    }

    @Test
    void unsupportedRoleDoesNotReachIngestion() throws Exception {
        mvc.perform(post("/add").contentType(MediaType.APPLICATION_JSON).content("""
                {"request_id":"request-1","user_id":"user-1","session_id":"session-1",
                 "messages":[{"role":"system","content":"not a source role"}]}
                """))
                .andExpect(status().isUnprocessableContent());
        assertThat(addService.lastRequest).isNull();
    }

    @Test
    void nonPositiveTopKDoesNotReachRetrieval() throws Exception {
        mvc.perform(post("/search").contentType(MediaType.APPLICATION_JSON).content("""
                {"user_id":"user-1","query":"Milo","top_k":0}
                """))
                .andExpect(status().isUnprocessableContent());
        assertThat(searchService.lastRequest).isNull();
    }

    @Test
    void malformedJsonReturnsClientError() throws Exception {
        mvc.perform(post("/add").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("INVALID_JSON"));
    }

    @Test
    void addReleasesServletThreadAndWaitsForActualCompletion() throws Exception {
        addService.pending = new CompletableFuture<>();
        var result = mvc.perform(post("/add").contentType(MediaType.APPLICATION_JSON).content(validAdd()))
                .andReturn();
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertThat(addService.pending).isNotCompleted();
        addService.pending.complete(new AddResponse(true, "waiting", "u", "s"));
        result.getAsyncResult(1000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void httpWaitTimeoutReturnsErrorWithoutCancellingTheJob() throws Exception {
        addService.pending = new CompletableFuture<>();
        var result = mvc.perform(post("/add").contentType(MediaType.APPLICATION_JSON).content(validAdd()))
                .andReturn();
        MockAsyncContext async = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : async.getListeners()) listener.onTimeout(new AsyncEvent(async));
        assertThat(result.getAsyncResult(1000)).isInstanceOf(AddAgentException.class);
        mvc.perform(asyncDispatch(result)).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error_code").value("ADD_HTTP_WAIT_TIMEOUT"));
        assertThat(addService.pending).isNotCompleted();
        assertThat(addService.pending.complete(new AddResponse(true, "waiting", "u", "s"))).isTrue();
    }

    private String validAdd() {
        return """
                {"request_id":"waiting","user_id":"u","session_id":"s",
                 "messages":[{"role":"user","content":"a fact"}]}
                """;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestServices {
        @Bean
        @Primary
        TestAddService testAddService() {
            return new TestAddService();
        }

        @Bean
        @Primary
        TestSearchService testSearchService() {
            return new TestSearchService();
        }
    }

    private ResultActions perform(RequestBuilder request) throws Exception {
        var action = mvc.perform(request);
        var result = action.andReturn();
        if (!result.getRequest().isAsyncStarted()) return action;
        result.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(result));
    }

    static class TestAddService implements AddService {
        private boolean respond;
        private AddRequest lastRequest;
        private CompletableFuture<AddResponse> pending;

        @Override public CompletableFuture<AddResponse> addAsync(AddRequest request) {
            if (pending == null) return AddService.super.addAsync(request);
            lastRequest = request;
            return pending;
        }

        @Override
        public AddResponse add(AddRequest request) {
            lastRequest = request;
            if (!respond) {
                return AddService.super.add(request);
            }
            return new AddResponse(true, request.requestId(), request.userId(), request.sessionId());
        }
    }

    static class TestSearchService implements SearchService {
        private boolean respond;
        private SearchRequest lastRequest;

        @Override
        public SearchResponse search(SearchRequest request) {
            lastRequest = request;
            if (!respond) {
                return SearchService.super.search(request);
            }
            return new SearchResponse(List.of(new EvidenceCard("event-1", request.query(), null, null)));
        }
    }
}

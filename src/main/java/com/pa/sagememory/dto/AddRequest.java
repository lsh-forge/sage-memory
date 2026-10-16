package com.pa.sagememory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AddRequest(
        @JsonProperty("request_id") String requestId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("session_id") String sessionId,
        List<AddMessage> messages) {
}

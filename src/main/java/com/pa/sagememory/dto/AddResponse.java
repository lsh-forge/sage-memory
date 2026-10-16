package com.pa.sagememory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Success may be returned only after durable, immediately searchable ingestion. */
public record AddResponse(
        boolean success,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("session_id") String sessionId) {
}

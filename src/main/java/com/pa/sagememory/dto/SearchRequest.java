package com.pa.sagememory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record SearchRequest(
        @JsonProperty("user_id") String userId,
        JsonNode query,
        @JsonProperty("top_k") Integer topK,
        List<String> options) {
}

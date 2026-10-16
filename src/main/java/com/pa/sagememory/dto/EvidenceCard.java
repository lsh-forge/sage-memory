package com.pa.sagememory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

/** Retrieved evidence only. Content retains the string or ordered multimodal shape. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceCard(
        String id,
        JsonNode content,
        Double score,
        @JsonProperty("created_at") String createdAt) {
}

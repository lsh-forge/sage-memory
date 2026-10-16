package com.pa.sagememory.controller;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Inbound API credential, separate from model-provider keys and the competition Eval Key. */
@Component
@ConfigurationProperties(prefix = "sage.memory.api")
public class ApiKeyProperties {
    private String apiKey;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }

    @PostConstruct
    public void validate() {
        if (apiKey == null || apiKey.length() < 32 || apiKey.chars().anyMatch(value -> value <= 32 || value > 126)) {
            throw new IllegalStateException("Configure sage.memory.api.api-key with at least 32 printable non-whitespace ASCII characters.");
        }
    }
}

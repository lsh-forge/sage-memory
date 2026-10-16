package com.pa.sagememory.dto;

import tools.jackson.databind.JsonNode;

/** 原始消息：timestamp 为 Unix 毫秒，可为空；content 为字符串或有序 text/image_url 数组。 */
public record AddMessage(String role, Long timestamp, JsonNode content) {
}

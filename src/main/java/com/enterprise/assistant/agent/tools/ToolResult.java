package com.enterprise.assistant.agent.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 工具的统一返回格式（contracts/agent-tools.md「通用约定」）：
 * {@code {"ok":true,"summary":"…","data":…}} 或 {@code {"ok":false,"error":"…","candidates":[…]}}。
 * 出错时不抛异常给模型，让模型据此向用户说明或追问。
 */
public final class ToolResult {

    /** 列表类结果最多返回的条数。 */
    public static final int MAX_ITEMS = 50;

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ToolResult() {
    }

    public static String ok(String summary, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("summary", summary);
        body.put("data", data);
        return write(body);
    }

    /** 列表结果：超过 50 条时只返回前 50 条，并附 total 与提示。 */
    public static String okList(String summary, List<?> items) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", items.size());
        data.put("items", items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items);
        if (items.size() > MAX_ITEMS) {
            data.put("note", "结果较多，只返回前 " + MAX_ITEMS + " 条，请缩小范围");
        }
        return ok(summary, data);
    }

    public static String error(String message) {
        return error(message, List.of());
    }

    public static String error(String message, List<?> candidates) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", message);
        if (!candidates.isEmpty()) {
            body.put("candidates", candidates);
        }
        return write(body);
    }

    /** 解析工具返回值，用于生成页面上的步骤摘要。 */
    public static Parsed parse(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            if (node != null && node.has("ok")) {
                boolean ok = node.get("ok").asBoolean();
                String text = ok ? node.path("summary").asText("完成") : node.path("error").asText("失败");
                return new Parsed(ok, text);
            }
        } catch (JsonProcessingException e) {
            // 非统一格式的返回值按成功处理
        }
        return new Parsed(true, "完成");
    }

    public record Parsed(boolean ok, String summary) {
    }

    private static String write(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

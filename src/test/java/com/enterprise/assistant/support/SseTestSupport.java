package com.enterprise.assistant.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 解析 MockMvc 返回的 SSE 响应。 */
public final class SseTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Event(String name, JsonNode data) {
    }

    private SseTestSupport() {
    }

    /** 等待事件流结束（出现 done 事件），返回全部事件。 */
    public static List<Event> awaitEvents(MvcResult result, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            List<Event> events = parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            if (!events.isEmpty() && events.get(events.size() - 1).name().equals("done")) {
                return events;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("事件流在 " + timeout + " 内没有结束，当前内容：\n"
                + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    public static List<Event> parse(String body) throws Exception {
        List<Event> events = new ArrayList<>();
        String name = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("event:")) {
                name = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5));
            } else if (line.isEmpty() && name != null) {
                events.add(new Event(name, JSON.readTree(data.length() == 0 ? "{}" : data.toString())));
                name = null;
                data.setLength(0);
            }
        }
        return events;
    }

    public static List<String> names(List<Event> events) {
        return events.stream().map(Event::name).toList();
    }
}

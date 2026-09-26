package com.enterprise.assistant.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 脚本化的模型替身：按预设队列依次返回「工具调用」或「最终回答」，并记录收到的每个 Prompt。
 */
public class ScriptedChatModel implements ChatModel {

    private sealed interface Step permits ToolCalls, Answer, Failure, Delay {
    }

    private record ToolCalls(List<AssistantMessage.ToolCall> calls) implements Step {
    }

    private record Answer(String text) implements Step {
    }

    private record Failure(RuntimeException exception) implements Step {
    }

    private record Delay(Duration duration, Step then) implements Step {
    }

    private final Deque<Step> script = new ConcurrentLinkedDeque<>();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final AtomicInteger callIds = new AtomicInteger();

    /** 清空脚本与记录，每个测试开始时调用。 */
    public void reset() {
        script.clear();
        prompts.clear();
    }

    /** 下一次调用返回一个工具调用；argumentsJson 为工具参数 JSON。 */
    public ScriptedChatModel thenCallTool(String toolName, String argumentsJson) {
        return thenCallTools(Map.of(toolName, argumentsJson));
    }

    /** 下一次调用在同一个回复中返回多个工具调用。 */
    public ScriptedChatModel thenCallTools(Map<String, String> toolsAndArguments) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        toolsAndArguments.forEach((name, args) ->
                calls.add(new AssistantMessage.ToolCall("call_" + callIds.incrementAndGet(), "function", name, args)));
        script.add(new ToolCalls(calls));
        return this;
    }

    /** 下一次调用返回最终回答。 */
    public ScriptedChatModel thenAnswer(String text) {
        script.add(new Answer(text));
        return this;
    }

    /** 下一次调用抛出异常，模拟模型服务不可用。 */
    public ScriptedChatModel thenFail(RuntimeException exception) {
        script.add(new Failure(exception));
        return this;
    }

    /** 下一次调用先等待一段时间再返回回答，模拟模型响应缓慢。 */
    public ScriptedChatModel thenAnswerAfter(Duration delay, String text) {
        script.add(new Delay(delay, new Answer(text)));
        return this;
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public Prompt lastPrompt() {
        return prompts.get(prompts.size() - 1);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        Step step = script.poll();
        if (step == null) {
            throw new IllegalStateException("ScriptedChatModel 的脚本已用完，但模型又被调用了一次");
        }
        return respond(step);
    }

    private ChatResponse respond(Step step) {
        return switch (step) {
            case ToolCalls tc -> new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("").toolCalls(tc.calls()).build())));
            case Answer a -> new ChatResponse(List.of(new Generation(new AssistantMessage(a.text()))));
            case Failure f -> throw f.exception();
            case Delay d -> {
                try {
                    Thread.sleep(d.duration());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                yield respond(d.then());
            }
        };
    }
}

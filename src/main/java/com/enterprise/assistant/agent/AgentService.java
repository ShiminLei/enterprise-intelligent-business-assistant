package com.enterprise.assistant.agent;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.enterprise.assistant.agent.AgentEvent.Answer;
import com.enterprise.assistant.agent.AgentEvent.Done;
import com.enterprise.assistant.agent.AgentEvent.Error;
import com.enterprise.assistant.agent.AgentEvent.MessageAccepted;
import com.enterprise.assistant.agent.tools.AgentTools;
import com.enterprise.assistant.conversation.Citation;
import com.enterprise.assistant.conversation.ConversationService;
import com.enterprise.assistant.conversation.MessageRole;
import com.enterprise.assistant.conversation.MessageStatus;
import com.enterprise.assistant.conversation.Step;
import com.enterprise.assistant.security.CurrentMember;

/**
 * 单 Agent + 工具调用的处理循环（research R3）：关闭 Spring AI 的内部工具执行，自行循环
 * 「调用模型 → 执行工具 → 交回模型」，以便限制调用次数、逐步推送进度并记录每次调用。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    static final String MODEL_UNAVAILABLE_MESSAGE = "AI 服务暂时不可用，请稍后重试";

    private static final Pattern CITATION_REF = Pattern.compile("\\[(\\d{1,3})]");

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> toolCallbacks;
    private final ConversationService conversations;
    private final Clock clock;
    private final AgentProperties properties;
    private final PromptTemplate systemPrompt;
    private final String modelName;
    private final List<AssistantMessageListener> listeners;

    public AgentService(ChatModel chatModel, ToolCallingManager toolCallingManager, List<AgentTools> tools,
                        ConversationService conversations, Clock clock, AgentProperties properties,
                        @Value("classpath:prompts/system-prompt.st") Resource systemPrompt,
                        @Value("${spring.ai.openai.chat.options.model:unknown}") String modelName,
                        List<AssistantMessageListener> listeners) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.toolCallbacks = tools.isEmpty() ? List.of() : Arrays.asList(ToolCallbacks.from(tools.toArray()));
        this.conversations = conversations;
        this.clock = clock;
        this.properties = properties;
        this.systemPrompt = new PromptTemplate(systemPrompt);
        this.modelName = modelName;
        this.listeners = List.copyOf(listeners);
    }

    /**
     * 处理一条用户消息：保存消息 → 推送 message_accepted → 工具循环 → 保存并推送回答 → done。
     *
     * @param expiredActionIds 因本条消息而作废的待确认操作
     */
    public void handle(CurrentMember member, long conversationId, String content, List<Long> expiredActionIds,
                       AgentEventSink sink) {
        var userMessage = conversations.saveUserMessage(conversationId, content);
        sink.send(new MessageAccepted(userMessage.getId(), expiredActionIds));

        AgentRequestContext context = new AgentRequestContext(member, conversationId, sink);
        try {
            Outcome outcome = runLoop(context);
            List<Citation> citations = referencedCitations(outcome.content(), context.citations());
            var saved = conversations.saveAssistantMessage(conversationId, outcome.content(), context.steps(),
                    citations, outcome.status());
            listeners.forEach(l -> l.onAssistantMessageSaved(saved.getId(), context));
            sink.send(new Answer(saved.getId(), outcome.content(), outcome.status(), citations));
        } catch (RuntimeException e) {
            log.error("agent request failed conversationId={} reason={}", conversationId, e.getMessage(), e);
            conversations.saveAssistantMessage(conversationId, MODEL_UNAVAILABLE_MESSAGE, context.steps(),
                    context.citations(), MessageStatus.FAILED);
            sink.send(new Error("MODEL_UNAVAILABLE", MODEL_UNAVAILABLE_MESSAGE));
        }
        sink.send(new Done());
    }

    private record Outcome(String content, MessageStatus status) {
    }

    private Outcome runLoop(AgentRequestContext context) {
        RecordingToolCallback.Budget budget = new RecordingToolCallback.Budget(properties.maxToolCalls());
        List<ToolCallback> callbacks = toolCallbacks.stream()
                .<ToolCallback>map(cb -> new RecordingToolCallback(cb, context, budget))
                .toList();
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .internalToolExecutionEnabled(false)
                .toolContext(context.asToolContext())
                .build();

        Prompt prompt = new Prompt(buildMessages(context), options);
        ChatResponse response = callModel(prompt);
        while (response.hasToolCalls()) {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            if (budget.exceeded()) {
                return new Outcome(limitReachedAnswer(context.steps()), MessageStatus.STEP_LIMIT_REACHED);
            }
            prompt = new Prompt(result.conversationHistory(), options);
            response = callModel(prompt);
        }
        String text = response.getResult() == null ? null : response.getResult().getOutput().getText();
        return new Outcome(text == null || text.isBlank() ? "（没有生成回答）" : text, MessageStatus.COMPLETED);
    }

    private List<Message> buildMessages(AgentRequestContext context) {
        CurrentMember member = context.member();
        String system = systemPrompt.render(Map.of(
                "currentUserName", member.name(),
                "currentUserRole", member.role().label(),
                "today", LocalDate.now(clock).toString()));
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        for (var m : conversations.recentHistory(context.conversationId(), properties.historySize())) {
            messages.add(m.getRole() == MessageRole.USER
                    ? new UserMessage(m.getContent())
                    : new AssistantMessage(m.getContent()));
        }
        return messages;
    }

    private ChatResponse callModel(Prompt prompt) {
        long start = System.nanoTime();
        ChatResponse response = chatModel.call(prompt);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        String model = response.getMetadata() != null && response.getMetadata().getModel() != null
                && !response.getMetadata().getModel().isBlank() ? response.getMetadata().getModel() : modelName;
        log.info("model call model={} durationMs={} promptTokens={} completionTokens={}", model, durationMs,
                usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens());
        return response;
    }

    /**
     * 回答中用 [n] 引用了哪些来源，就只展示这些；一个都没引用时展示全部检索到的来源（FR-008）。
     */
    static List<Citation> referencedCitations(String content, List<Citation> all) {
        Set<Integer> referenced = new HashSet<>();
        Matcher m = CITATION_REF.matcher(content == null ? "" : content);
        while (m.find()) {
            referenced.add(Integer.parseInt(m.group(1)));
        }
        List<Citation> used = all.stream().filter(c -> referenced.contains(c.index())).toList();
        return used.isEmpty() ? all : used;
    }

    private String limitReachedAnswer(List<Step> steps) {
        StringBuilder sb = new StringBuilder()
                .append("已达到单次请求最多 ").append(properties.maxToolCalls())
                .append(" 次工具调用的上限，处理已停止。\n\n已完成的步骤：\n");
        for (Step step : steps) {
            sb.append(step.seq()).append(". ").append(step.label()).append("：").append(step.resultSummary()).append('\n');
        }
        return sb.append("\n请把问题拆小或缩小范围后重试。").toString();
    }
}

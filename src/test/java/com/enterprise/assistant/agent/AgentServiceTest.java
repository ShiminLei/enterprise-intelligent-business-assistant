package com.enterprise.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.ClassPathResource;

import com.enterprise.assistant.agent.AgentEvent.Answer;
import com.enterprise.assistant.agent.AgentEvent.Error;
import com.enterprise.assistant.agent.AgentEvent.StepFinished;
import com.enterprise.assistant.agent.AgentEvent.StepStarted;
import com.enterprise.assistant.agent.tools.AgentTools;
import com.enterprise.assistant.agent.tools.ToolResult;
import com.enterprise.assistant.conversation.ConversationService;
import com.enterprise.assistant.conversation.Message;
import com.enterprise.assistant.conversation.MessageRole;
import com.enterprise.assistant.conversation.MessageStatus;
import com.enterprise.assistant.member.MemberRole;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.support.ScriptedChatModel;

class AgentServiceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-26T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    static final CurrentMember WANG = new CurrentMember(1, "wangjl", "x", "王经理", MemberRole.PROJECT_MANAGER);

    /** 测试用假工具：记录收到的当前成员。 */
    static class FakeTools implements AgentTools {
        final List<String> seenMembers = new CopyOnWriteArrayList<>();

        @Tool(description = "回显输入")
        String echo(String text, ToolContext toolContext) {
            seenMembers.add(AgentRequestContext.from(toolContext).member().name());
            return ToolResult.ok("回显：" + text, text);
        }

        @Tool(description = "登记一个待确认操作")
        String propose(String text, ToolContext toolContext) {
            AgentRequestContext.from(toolContext).registerPendingAction(55L);
            return ToolResult.ok("已生成待确认操作", 55);
        }

        @Tool(description = "总是失败")
        String broken(String text) {
            throw new IllegalStateException("数据库连接失败");
        }
    }

    ScriptedChatModel model;
    FakeTools tools;
    ConversationService conversations;
    AgentService agent;
    List<AgentEvent> events;
    final List<String> savedLinks = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        model = new ScriptedChatModel();
        tools = new FakeTools();
        conversations = mock(ConversationService.class);
        Message userMessage = message(MessageRole.USER, "当前问题");
        when(userMessage.getId()).thenReturn(100L);
        when(conversations.saveUserMessage(anyLong(), anyString())).thenReturn(userMessage);
        Message assistantMessage = mock(Message.class);
        when(assistantMessage.getId()).thenReturn(101L);
        when(conversations.saveAssistantMessage(anyLong(), anyString(), any(), any(), any())).thenReturn(assistantMessage);
        List<Message> history = List.of(message(MessageRole.USER, "上一个问题"),
                message(MessageRole.ASSISTANT, "上一个回答"), message(MessageRole.USER, "当前问题"));
        when(conversations.recentHistory(anyLong(), anyInt())).thenReturn(history);

        agent = new AgentService(model, ToolCallingManager.builder().build(), List.of(tools), conversations, CLOCK,
                new AgentProperties(10, Duration.ofSeconds(120), 20),
                new ClassPathResource("prompts/system-prompt.st"), "qwen-plus",
                List.of((messageId, context) -> savedLinks.add(messageId + ":" + context.pendingActionIds())));
        events = new CopyOnWriteArrayList<>();
    }

    private void run() {
        agent.handle(WANG, 7L, "当前问题", List.of(), events::add);
    }

    @Test
    void directAnswerHasNoSteps() {
        model.thenAnswer("你好，我是项目管理助手。");
        run();

        assertThat(events).extracting(AgentEvent::name).containsExactly("message_accepted", "answer", "done");
        Answer answer = (Answer) events.get(1);
        assertThat(answer.content()).isEqualTo("你好，我是项目管理助手。");
        assertThat(answer.status()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(answer.assistantMessageId()).isEqualTo(101L);
    }

    @Test
    void eachToolCallProducesStartedAndFinishedEvents() {
        model.thenCallTool("echo", "{\"text\":\"一\"}")
                .thenCallTool("echo", "{\"text\":\"二\"}")
                .thenAnswer("完成");
        run();

        assertThat(events).extracting(AgentEvent::name).containsExactly(
                "message_accepted", "step_started", "step_finished", "step_started", "step_finished", "answer", "done");
        StepStarted first = (StepStarted) events.get(1);
        StepFinished second = (StepFinished) events.get(4);
        assertThat(first.seq()).isEqualTo(1);
        assertThat(first.tool()).isEqualTo("echo");
        assertThat(second.seq()).isEqualTo(2);
        assertThat(second.success()).isTrue();
        assertThat(second.resultSummary()).isEqualTo("回显：二");

        ArgumentCaptor<List<com.enterprise.assistant.conversation.Step>> steps = ArgumentCaptor.captor();
        verify(conversations).saveAssistantMessage(eq(7L), eq("完成"), steps.capture(), any(), eq(MessageStatus.COMPLETED));
        assertThat(steps.getValue()).hasSize(2);
    }

    @Test
    void stopsAtToolCallLimit() {
        IntStream.range(0, 11).forEach(i -> model.thenCallTool("echo", "{\"text\":\"" + i + "\"}"));
        run();

        assertThat(events).filteredOn(e -> e instanceof StepStarted).hasSize(10);
        Answer answer = (Answer) events.stream().filter(e -> e instanceof Answer).findFirst().orElseThrow();
        assertThat(answer.status()).isEqualTo(MessageStatus.STEP_LIMIT_REACHED);
        assertThat(answer.content()).contains("10 次").contains("已完成的步骤");
        assertThat(model.prompts()).hasSize(11);
    }

    @Test
    void toolFailureIsReportedAndReturnedToModel() {
        model.thenCallTool("broken", "{\"text\":\"x\"}").thenAnswer("抱歉，查询失败了");
        run();

        StepFinished finished = (StepFinished) events.stream().filter(e -> e instanceof StepFinished).findFirst().orElseThrow();
        assertThat(finished.success()).isFalse();
        assertThat(finished.resultSummary()).contains("数据库连接失败");
        String toolResponse = model.lastPrompt().getInstructions().stream()
                .filter(m -> m.getMessageType() == MessageType.TOOL)
                .map(Object::toString).findFirst().orElseThrow();
        assertThat(toolResponse).contains("数据库连接失败");
        assertThat(events).extracting(AgentEvent::name).contains("answer");
    }

    @Test
    void modelFailureProducesErrorEventAfterSavingUserMessage() {
        model.thenFail(new RuntimeException("connection refused"));
        run();

        verify(conversations).saveUserMessage(7L, "当前问题");
        assertThat(events).extracting(AgentEvent::name).containsExactly("message_accepted", "error", "done");
        assertThat(((Error) events.get(1)).code()).isEqualTo("MODEL_UNAVAILABLE");
        verify(conversations).saveAssistantMessage(eq(7L), anyString(), any(), any(), eq(MessageStatus.FAILED));
    }

    @Test
    void promptContainsSystemPromptUserIdentityTodayAndHistory() {
        model.thenAnswer("好的");
        run();

        List<org.springframework.ai.chat.messages.Message> instructions = model.lastPrompt().getInstructions();
        String system = instructions.get(0).getText();
        assertThat(instructions.get(0).getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(system).contains("王经理").contains("项目经理").contains("2026-09-26");
        assertThat(instructions.subList(1, instructions.size()))
                .extracting(AbstractMessage.class::cast)
                .extracting(AbstractMessage::getText)
                .containsExactly("上一个问题", "上一个回答", "当前问题");
        verify(conversations).recentHistory(7L, 20);
    }

    @Test
    void currentMemberIsPassedToToolsThroughToolContext() {
        model.thenCallTool("echo", "{\"text\":\"hi\"}").thenAnswer("完成");
        run();

        assertThat(tools.seenMembers).containsExactly("王经理");
    }

    @Test
    void pendingActionsAreLinkedToTheSavedAssistantMessage() {
        model.thenCallTool("propose", "{\"text\":\"x\"}").thenAnswer("请确认");
        run();

        assertThat(savedLinks).containsExactly("101:[55]");
    }

    private static Message message(MessageRole role, String content) {
        Message m = mock(Message.class);
        when(m.getRole()).thenReturn(role);
        when(m.getContent()).thenReturn(content);
        return m;
    }
}

package com.enterprise.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.common.ErrorCode;
import com.enterprise.assistant.security.CurrentMember;
import com.enterprise.assistant.security.MemberUserDetailsService;
import com.enterprise.assistant.support.AbstractIntegrationTest;

@Transactional
class ConversationServiceIT extends AbstractIntegrationTest {

    @Autowired
    ConversationService conversations;

    @Autowired
    MemberUserDetailsService users;

    @Test
    void membersOnlySeeTheirOwnConversations() {
        CurrentMember wang = users.loadUserByUsername("wangjl");
        CurrentMember zhang = users.loadUserByUsername("zhangsan");
        Conversation mine = conversations.create(wang);
        Conversation theirs = conversations.create(zhang);

        assertThat(conversations.listOwn(wang)).extracting(Conversation::getId)
                .contains(mine.getId()).doesNotContain(theirs.getId());
        assertThatThrownBy(() -> conversations.getOwn(wang, theirs.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> conversations.listMessages(wang, theirs.getId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listIsOrderedByMostRecentlyUpdated() {
        CurrentMember wang = users.loadUserByUsername("wangjl");
        Conversation older = conversations.create(wang);
        Conversation newer = conversations.create(wang);
        conversations.saveUserMessage(older.getId(), "更新旧会话");

        List<Long> ids = conversations.listOwn(wang).stream().map(Conversation::getId).toList();
        assertThat(ids.indexOf(older.getId())).isLessThan(ids.indexOf(newer.getId()));
    }

    @Test
    void titleIsFirstThirtyCharactersOfFirstUserMessage() {
        CurrentMember wang = users.loadUserByUsername("wangjl");
        Conversation c = conversations.create(wang);
        String first = "查询项目A目前有哪些延期任务，并结合公司的风险管理规定生成一份风险分析报告，谢谢";
        conversations.saveUserMessage(c.getId(), first);
        conversations.saveUserMessage(c.getId(), "第二条消息不改变标题");

        assertThat(conversations.getOwn(wang, c.getId()).getTitle()).isEqualTo(first.substring(0, 30));
    }

    @Test
    void recentHistoryReturnsLastMessagesInChronologicalOrder() {
        CurrentMember wang = users.loadUserByUsername("wangjl");
        Conversation c = conversations.create(wang);
        for (int i = 1; i <= 12; i++) {
            conversations.saveUserMessage(c.getId(), "问题" + i);
            conversations.saveAssistantMessage(c.getId(), "回答" + i, List.of(), List.of(), MessageStatus.COMPLETED);
        }
        conversations.saveAssistantMessage(c.getId(), "AI 服务暂时不可用", List.of(), List.of(), MessageStatus.FAILED);

        List<Message> history = conversations.recentHistory(c.getId(), 20);
        assertThat(history).hasSize(20);
        assertThat(history.get(0).getContent()).isEqualTo("问题3");
        assertThat(history.get(19).getContent()).isEqualTo("回答12");
        assertThat(history).noneMatch(m -> m.getStatus() == MessageStatus.FAILED);
    }

    @Test
    void stepsAndCitationsRoundTripAsJson() {
        CurrentMember wang = users.loadUserByUsername("wangjl");
        Conversation c = conversations.create(wang);
        Step step = new Step(1, "queryTasks", "查询任务", "{\"overdueOnly\":true}", "找到 3 个延期任务", true, 120);
        Citation citation = new Citation(1, "项目风险管理规定", "二、延期风险等级", "延期超过 7 天为高风险");
        Message saved = conversations.saveAssistantMessage(c.getId(), "回答", List.of(step), List.of(citation),
                MessageStatus.COMPLETED);

        Message loaded = conversations.listMessages(wang, c.getId()).stream()
                .filter(m -> m.getId().equals(saved.getId())).findFirst().orElseThrow();
        assertThat(loaded.getSteps()).containsExactly(step);
        assertThat(loaded.getCitations()).containsExactly(citation);
        assertThat(loaded.getRole()).isEqualTo(MessageRole.ASSISTANT);
    }
}

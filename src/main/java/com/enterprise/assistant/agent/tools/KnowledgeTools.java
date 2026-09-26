package com.enterprise.assistant.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.enterprise.assistant.agent.AgentRequestContext;
import com.enterprise.assistant.knowledge.KnowledgeHealthIndicator;
import com.enterprise.assistant.knowledge.KnowledgeSearchService;
import com.enterprise.assistant.knowledge.KnowledgeSearchService.Chunk;
import com.enterprise.assistant.knowledge.KnowledgeUnavailableException;

/**
 * 检索知识库工具（FR-007 ~ FR-009），契约见 contracts/agent-tools.md「searchKnowledge」。
 * 检索到的片段由服务端登记为本次回答的引用来源，出处不依赖模型生成（research R5）。
 */
@Component
public class KnowledgeTools implements AgentTools {

    static final int EXCERPT_LENGTH = 200;

    private final KnowledgeSearchService search;
    private final KnowledgeHealthIndicator health;

    public KnowledgeTools(KnowledgeSearchService search, KnowledgeHealthIndicator health) {
        this.search = search;
        this.health = health;
    }

    @Tool(name = "searchKnowledge",
            description = "检索公司制度文档（风险管理规定、项目管理制度等）。凡是涉及公司规定、流程、标准的问题，MUST 先调用本工具，"
                    + "并只根据返回内容作答；返回为空时如实告诉用户未找到相关规定。")
    public String searchKnowledge(@ToolParam(description = "检索语句") String query, ToolContext toolContext) {
        AgentRequestContext context = AgentRequestContext.from(toolContext);
        List<Chunk> chunks;
        try {
            chunks = search.search(query);
        } catch (KnowledgeUnavailableException e) {
            return ToolResult.error("知识库暂不可用");
        }
        if (chunks.isEmpty() && !health.isAvailable()) {
            return ToolResult.error("知识库暂不可用");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Chunk chunk : chunks) {
            int ref = context.registerCitation(chunk.id(), chunk.documentName(), chunk.section(), excerpt(chunk));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", ref);
            item.put("documentName", chunk.documentName());
            item.put("section", chunk.section());
            item.put("content", chunk.content());
            items.add(item);
        }
        String summary = items.isEmpty() ? "未找到相关规定" : "找到 " + items.size() + " 条相关规定";
        return ToolResult.ok(summary, items);
    }

    /** 引用中展示的原文片段：去掉片段开头的章节标题行，截取前 200 字。 */
    private static String excerpt(Chunk chunk) {
        String text = chunk.content();
        if (!chunk.section().isEmpty() && text.startsWith(chunk.section())) {
            text = text.substring(chunk.section().length()).strip();
        }
        return text.length() <= EXCERPT_LENGTH ? text : text.substring(0, EXCERPT_LENGTH) + "…";
    }
}

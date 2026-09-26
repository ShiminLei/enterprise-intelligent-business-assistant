package com.enterprise.assistant.agent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import com.enterprise.assistant.agent.AgentEvent.StepFinished;
import com.enterprise.assistant.agent.AgentEvent.StepStarted;
import com.enterprise.assistant.agent.tools.ToolLabels;
import com.enterprise.assistant.agent.tools.ToolResult;
import com.enterprise.assistant.conversation.Step;

/**
 * 包装一个工具：执行前后推送步骤事件、记录耗时与成败日志（宪法 IV），
 * 并在达到单次请求的调用上限后拒绝执行（FR-019）。工具抛出的异常转为错误结果交回模型。
 */
final class RecordingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RecordingToolCallback.class);

    /** 同一次请求内的所有工具共享的调用计数。 */
    static final class Budget {
        private final int max;
        private final AtomicInteger used = new AtomicInteger();
        private final AtomicBoolean exceeded = new AtomicBoolean();

        Budget(int max) {
            this.max = max;
        }

        /** 占用一次调用额度，返回步骤序号；超过上限返回 -1。 */
        int acquire() {
            int seq = used.incrementAndGet();
            if (seq > max) {
                exceeded.set(true);
                return -1;
            }
            return seq;
        }

        boolean exceeded() {
            return exceeded.get();
        }

        int max() {
            return max;
        }
    }

    private final ToolCallback delegate;
    private final AgentRequestContext context;
    private final Budget budget;

    RecordingToolCallback(ToolCallback delegate, AgentRequestContext context, Budget budget) {
        this.delegate = delegate;
        this.context = context;
        this.budget = budget;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String tool = getToolDefinition().name();
        int seq = budget.acquire();
        if (seq < 0) {
            log.warn("tool call rejected: limit reached tool={} max={}", tool, budget.max());
            return ToolResult.error("已达到单次请求最多 " + budget.max() + " 次工具调用的上限，未执行");
        }
        String label = ToolLabels.labelOf(tool);
        context.send(new StepStarted(seq, tool, label));
        context.beginStep();

        long start = System.nanoTime();
        String result;
        ToolResult.Parsed parsed;
        try {
            result = delegate.call(toolInput, toolContext);
            parsed = ToolResult.parse(result);
        } catch (RuntimeException e) {
            String reason = rootMessage(e);
            result = ToolResult.error("工具执行失败：" + reason);
            parsed = new ToolResult.Parsed(false, "执行失败：" + reason);
            log.warn("tool call failed tool={} reason={}", tool, reason);
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        log.info("tool call tool={} durationMs={} success={}", tool, durationMs, parsed.ok());
        context.addStep(new Step(seq, tool, label, toolInput, parsed.summary(), parsed.ok(), durationMs));
        context.send(new StepFinished(seq, parsed.ok(), parsed.summary(), durationMs));
        context.endStep();
        return result;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}

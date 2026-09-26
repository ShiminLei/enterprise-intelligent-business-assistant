package com.enterprise.assistant.knowledge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.stereotype.Component;

/**
 * 按 Markdown 标题切分文档（research R7）：每个 #、##、### 标题下的正文为一个片段，片段以所属章节标题开头；
 * 超过约 500 Token 的章节按句子再切分，相邻片段保留约 50 Token 重叠。
 * Spring AI 的 TokenTextSplitter 不支持重叠，因此自行实现，不引入额外的 Markdown 解析依赖。
 */
@Component
public class MarkdownChunker {

    static final int MAX_TOKENS = 500;
    static final int OVERLAP_TOKENS = 50;

    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；!?;\\n])");
    private static final TokenCountEstimator TOKENS = new JTokkitTokenCountEstimator();

    static int estimateTokens(String text) {
        return TOKENS.estimate(text);
    }

    private record Section(String title, String body) {
    }

    public List<Document> chunk(long documentId, String documentName, String markdown) {
        List<Document> chunks = new ArrayList<>();
        for (Section section : sections(markdown)) {
            for (String piece : split(section.body())) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("document_id", documentId);
                metadata.put("document_name", documentName);
                metadata.put("section", section.title());
                metadata.put("chunk_index", chunks.size());
                chunks.add(new Document(section.title() + "\n" + piece, metadata));
            }
        }
        return chunks;
    }

    private List<Section> sections(String markdown) {
        List<Section> sections = new ArrayList<>();
        String h1 = null;
        String h2 = null;
        String h3 = null;
        StringBuilder body = new StringBuilder();
        String currentTitle = null;
        for (String line : markdown.split("\\R")) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                addSection(sections, currentTitle, body);
                body.setLength(0);
                int level = m.group(1).length();
                String text = m.group(2).strip();
                if (level == 1) {
                    h1 = text;
                    h2 = null;
                    h3 = null;
                } else if (level == 2) {
                    h2 = text;
                    h3 = null;
                } else {
                    h3 = text;
                }
                currentTitle = h3 != null ? (h2 != null ? h2 + " / " + h3 : h3) : (h2 != null ? h2 : h1);
            } else {
                body.append(line).append('\n');
            }
        }
        addSection(sections, currentTitle, body);
        return sections;
    }

    private static void addSection(List<Section> sections, String title, StringBuilder body) {
        String text = body.toString().strip();
        if (!text.isEmpty()) {
            sections.add(new Section(title == null ? "" : title, text));
        }
    }

    /** 章节不超过上限时整体返回；否则按句子累积切分，并把上一片段末尾约 50 Token 的句子带入下一片段。 */
    private List<String> split(String body) {
        if (estimateTokens(body) <= MAX_TOKENS) {
            return List.of(body);
        }
        List<String> sentences = new ArrayList<>();
        for (String s : SENTENCE_END.split(body)) {
            if (s.isBlank()) {
                continue;
            }
            sentences.addAll(hardSplit(s));
        }

        List<String> pieces = new ArrayList<>();
        Deque<String> current = new ArrayDeque<>();
        int currentTokens = 0;
        for (String sentence : sentences) {
            int tokens = estimateTokens(sentence);
            if (currentTokens + tokens > MAX_TOKENS && !current.isEmpty()) {
                pieces.add(String.join("", current).strip());
                Deque<String> overlap = new ArrayDeque<>();
                int overlapTokens = 0;
                while (!current.isEmpty() && overlapTokens + estimateTokens(current.peekLast()) <= OVERLAP_TOKENS) {
                    String last = current.pollLast();
                    overlap.addFirst(last);
                    overlapTokens += estimateTokens(last);
                }
                current = overlap;
                currentTokens = overlapTokens;
            }
            current.addLast(sentence);
            currentTokens += tokens;
        }
        if (!current.isEmpty()) {
            pieces.add(String.join("", current).strip());
        }
        return pieces;
    }

    /** 单个句子超过上限时按字符硬切。 */
    private static List<String> hardSplit(String sentence) {
        if (estimateTokens(sentence) <= MAX_TOKENS) {
            return List.of(sentence);
        }
        List<String> parts = new ArrayList<>();
        int step = 200;
        for (int i = 0; i < sentence.length(); i += step) {
            parts.add(sentence.substring(i, Math.min(sentence.length(), i + step)));
        }
        return parts;
    }
}

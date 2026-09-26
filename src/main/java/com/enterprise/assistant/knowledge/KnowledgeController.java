package com.enterprise.assistant.knowledge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.security.CurrentMember;

/** 知识库文档接口（FR-006a），契约见 contracts/openapi.yaml。 */
@RestController
@RequestMapping("/api/knowledge/documents")
public class KnowledgeController {

    static final long MAX_BYTES = 1024 * 1024;

    public record KbDocumentView(long id, String name, KbDocumentSource source, int chunkCount, Instant importedAt) {
        static KbDocumentView of(KbDocument d) {
            return new KbDocumentView(d.getId(), d.getName(), d.getSource(), d.getChunkCount(), d.getImportedAt());
        }
    }

    private final KbDocumentRepository documents;
    private final KnowledgeIngestionService ingestion;

    public KnowledgeController(KbDocumentRepository documents, KnowledgeIngestionService ingestion) {
        this.documents = documents;
        this.ingestion = ingestion;
    }

    @GetMapping
    List<KbDocumentView> list() {
        return documents.findAllByOrderById().stream().map(KbDocumentView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    KbDocumentView upload(@AuthenticationPrincipal CurrentMember member, @RequestParam("file") MultipartFile file)
            throws IOException {
        if (!member.isProjectManager()) {
            throw BusinessException.forbidden("只有项目经理可以上传知识库文档");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        filename = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".md") || filename.length() <= 3) {
            throw BusinessException.badRequest("只支持 Markdown（.md）文档");
        }
        if (file.getSize() > MAX_BYTES) {
            throw BusinessException.badRequest("文件不能超过 1 MB");
        }
        String content = decodeUtf8(file.getBytes());
        if (content.isBlank()) {
            throw BusinessException.badRequest("文档内容为空");
        }
        String name = filename.substring(0, filename.length() - 3).strip();
        return KbDocumentView.of(ingestion.importDocument(name, content, KbDocumentSource.UPLOADED, member.id()).document());
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw BusinessException.badRequest("文档必须是 UTF-8 编码");
        }
    }
}

package com.enterprise.assistant.knowledge;

import com.enterprise.assistant.common.BusinessException;
import com.enterprise.assistant.common.ErrorCode;

/** 向量化或检索服务不可用；已有知识库保持不变。 */
public class KnowledgeUnavailableException extends BusinessException {

    public KnowledgeUnavailableException(String message, Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message);
        initCause(cause);
    }
}

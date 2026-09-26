-- 项目管理智能助手：数据库结构（见 specs/001-project-management-assistant/data-model.md）

CREATE EXTENSION IF NOT EXISTS vector;

-- 成员 / 登录账号
CREATE TABLE member (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    name          VARCHAR(50)  NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('PROJECT_MANAGER', 'TEAM_MEMBER'))
);

-- 项目
CREATE TABLE project (
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(20)  NOT NULL UNIQUE,
    name               VARCHAR(100) NOT NULL,
    description        VARCHAR(500),
    manager_id         BIGINT       NOT NULL REFERENCES member (id),
    planned_start_date DATE         NOT NULL,
    planned_end_date   DATE         NOT NULL,
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('PLANNING', 'IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT ck_project_dates CHECK (planned_end_date >= planned_start_date)
);

-- 任务
CREATE TABLE task (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(20)  NOT NULL UNIQUE,
    project_id     BIGINT       NOT NULL REFERENCES project (id),
    title          VARCHAR(200) NOT NULL CHECK (length(trim(title)) > 0),
    assignee_id    BIGINT       NOT NULL REFERENCES member (id),
    priority       VARCHAR(10)  NOT NULL CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    status         VARCHAR(20)  NOT NULL CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    due_date       DATE         NOT NULL,
    completed_date DATE,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_task_project ON task (project_id);
CREATE INDEX idx_task_assignee ON task (assignee_id);

-- 会话
CREATE TABLE conversation (
    id         BIGSERIAL PRIMARY KEY,
    owner_id   BIGINT      NOT NULL REFERENCES member (id),
    title      VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_conversation_owner ON conversation (owner_id, updated_at DESC);

-- 消息
CREATE TABLE message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversation (id),
    role            VARCHAR(10) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content         TEXT        NOT NULL,
    steps           JSONB,
    citations       JSONB,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('COMPLETED', 'FAILED', 'STEP_LIMIT_REACHED')),
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_message_conversation ON message (conversation_id, id);

-- 待确认操作（同时作为写操作审计记录）
CREATE TABLE pending_action (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT       NOT NULL REFERENCES conversation (id),
    message_id      BIGINT REFERENCES message (id),
    requested_by    BIGINT       NOT NULL REFERENCES member (id),
    type            VARCHAR(30)  NOT NULL CHECK (type IN ('CREATE_TASK', 'UPDATE_TASK_STATUS')),
    payload         JSONB        NOT NULL,
    summary         VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'EXECUTED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    result          VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    resolved_at     TIMESTAMPTZ,
    resolved_by     BIGINT REFERENCES member (id)
);
CREATE INDEX idx_pending_action_conversation ON pending_action (conversation_id, status);

-- 知识文档
CREATE TABLE kb_document (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(200) NOT NULL UNIQUE,
    source       VARCHAR(20)  NOT NULL CHECK (source IN ('BUILT_IN', 'UPLOADED')),
    content_hash CHAR(64)     NOT NULL,
    chunk_count  INT          NOT NULL,
    uploaded_by  BIGINT REFERENCES member (id),
    imported_at  TIMESTAMPTZ  NOT NULL
);

-- 知识片段（Spring AI PgVectorStore 标准表结构，text-embedding-v4 1024 维）
CREATE TABLE vector_store (
    id        UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(1024)
);
CREATE INDEX idx_vector_store_embedding ON vector_store USING hnsw (embedding vector_cosine_ops);

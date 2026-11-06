-- ragent v1.6 -> v1.7 升级脚本
-- PostgreSQL 真源、版本化 ES/LightRAG 异步投影和 workspace 绑定

ALTER TABLE t_knowledge_document
    ADD COLUMN IF NOT EXISTS content_version BIGINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN t_knowledge_document.content_version IS '检索内容版本，仅影响现有Chunk的变更递增';

CREATE TABLE IF NOT EXISTS t_rag_projection_state (
    id                   VARCHAR(20)  NOT NULL PRIMARY KEY,
    target               VARCHAR(24)  NOT NULL,
    scope_type           VARCHAR(24)  NOT NULL,
    entity_id            VARCHAR(64)  NOT NULL,
    kb_id                VARCHAR(20)  NOT NULL,
    collection_name      VARCHAR(64),
    doc_id               VARCHAR(20),
    desired_version      BIGINT       NOT NULL DEFAULT 0,
    applied_version      BIGINT       NOT NULL DEFAULT -1,
    action               VARCHAR(16)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    phase                VARCHAR(32),
    attempt_count        INTEGER      NOT NULL DEFAULT 0,
    next_retry_time      TIMESTAMP,
    lease_owner          VARCHAR(128),
    lease_until          TIMESTAMP,
    last_error           TEXT,
    external_track_id    VARCHAR(128),
    external_track_version BIGINT,
    external_document_id VARCHAR(128),
    config_fingerprint   VARCHAR(128),
    desired_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at           TIMESTAMP,
    completed_at         TIMESTAMP,
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_projection_entity UNIQUE (target, scope_type, entity_id),
    CONSTRAINT ck_projection_status CHECK (status IN
        ('PENDING', 'RUNNING', 'WAIT_EXTERNAL', 'RETRY_WAIT', 'SUCCEEDED', 'BLOCKED')),
    CONSTRAINT ck_projection_action CHECK (action IN ('UPSERT', 'DELETE', 'PURGE_KB'))
);
CREATE INDEX IF NOT EXISTS idx_projection_due ON t_rag_projection_state
    (target, status, next_retry_time, desired_time);
CREATE INDEX IF NOT EXISTS idx_projection_kb_doc ON t_rag_projection_state (kb_id, doc_id);
CREATE INDEX IF NOT EXISTS idx_projection_lease ON t_rag_projection_state (lease_until)
    WHERE status IN ('RUNNING', 'WAIT_EXTERNAL');
COMMENT ON TABLE t_rag_projection_state IS 'ES/LightRAG 可重建异步投影期望状态';

CREATE TABLE IF NOT EXISTS t_rag_projection_attempt (
    id              VARCHAR(20) NOT NULL PRIMARY KEY,
    projection_id   VARCHAR(20) NOT NULL,
    target          VARCHAR(24) NOT NULL,
    desired_version BIGINT      NOT NULL,
    attempt_no      INTEGER     NOT NULL,
    result          VARCHAR(20) NOT NULL,
    phase           VARCHAR(32),
    error_summary   TEXT,
    started_at      TIMESTAMP   NOT NULL,
    finished_at     TIMESTAMP,
    duration_ms     BIGINT,
    create_time     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_projection_attempt_state
    ON t_rag_projection_attempt (projection_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_projection_attempt_retention
    ON t_rag_projection_attempt (create_time);
COMMENT ON TABLE t_rag_projection_attempt IS '投影每次执行的审计记录，默认保留90天';

CREATE TABLE IF NOT EXISTS t_rag_projection_batch (
    id            VARCHAR(20) NOT NULL PRIMARY KEY,
    batch_type    VARCHAR(20) NOT NULL,
    target        VARCHAR(64),
    kb_id         VARCHAR(20),
    doc_id        VARCHAR(20),
    status        VARCHAR(20) NOT NULL,
    total_count   INTEGER     NOT NULL DEFAULT 0,
    success_count INTEGER     NOT NULL DEFAULT 0,
    failed_count  INTEGER     NOT NULL DEFAULT 0,
    pending_count INTEGER     NOT NULL DEFAULT 0,
    requested_by  VARCHAR(64),
    last_error    TEXT,
    started_at    TIMESTAMP,
    completed_at  TIMESTAMP,
    create_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_projection_batch_status
    ON t_rag_projection_batch (status, create_time DESC);
COMMENT ON TABLE t_rag_projection_batch IS '投影全量重建及对账批任务，默认保留180天';

CREATE TABLE IF NOT EXISTS t_graph_workspace_binding (
    id                   VARCHAR(20)  NOT NULL PRIMARY KEY,
    kb_id                VARCHAR(20)  NOT NULL,
    workspace_key        VARCHAR(64)  NOT NULL,
    enabled              SMALLINT     NOT NULL DEFAULT 0,
    health_status        VARCHAR(20)  NOT NULL DEFAULT 'DISABLED',
    lightrag_version     VARCHAR(64),
    api_version          VARCHAR(64),
    config_fingerprint   VARCHAR(128),
    last_health_time     TIMESTAMP,
    last_error           TEXT,
    lease_owner          VARCHAR(128),
    lease_until          TIMESTAMP,
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_graph_workspace_kb UNIQUE (kb_id),
    CONSTRAINT uk_graph_workspace_key UNIQUE (workspace_key)
);
CREATE INDEX IF NOT EXISTS idx_graph_workspace_health
    ON t_graph_workspace_binding (enabled, health_status);
CREATE INDEX IF NOT EXISTS idx_graph_workspace_lease
    ON t_graph_workspace_binding (lease_until);
COMMENT ON TABLE t_graph_workspace_binding IS '知识库与确定性LightRAG workspace绑定，不保存URL或密钥';

-- 新增公开体验游客账号；可重复执行，不修改其他用户
INSERT INTO t_user (id, username, password, role, avatar, create_time, update_time, deleted)
VALUES ('2001523723396308994', 'guest', 'guest', 'guest', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
ON CONFLICT (username) DO UPDATE
SET password = EXCLUDED.password,
    role = EXCLUDED.role,
    avatar = EXCLUDED.avatar,
    update_time = CURRENT_TIMESTAMP,
    deleted = 0;

COMMENT ON COLUMN t_user.role IS '角色：admin/user/guest';

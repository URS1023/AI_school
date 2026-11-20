-- 运营中心：消息、审核、敏感词、反馈工单、举报、内容管理

CREATE TABLE msg_message (
    id         BIGINT       NOT NULL PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    category   VARCHAR(20)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    content    VARCHAR(2000),
    biz_ref    VARCHAR(100),
    read_flag  TINYINT      NOT NULL DEFAULT 0,
    read_at    DATETIME,
    created_by BIGINT,
    created_at DATETIME,
    updated_at DATETIME
);
CREATE INDEX idx_msg_user ON msg_message (user_id, read_flag, created_at);

CREATE TABLE op_audit (
    id                BIGINT       NOT NULL PRIMARY KEY,
    biz_type          VARCHAR(30)  NOT NULL,
    biz_id            BIGINT       NOT NULL,
    tenant_id         BIGINT,
    title             VARCHAR(200),
    content           TEXT,
    submitter_user_id BIGINT,
    machine_result    VARCHAR(20),
    machine_hits      VARCHAR(500),
    audit_status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    reason            VARCHAR(500),
    auditor_id        BIGINT,
    audited_at        DATETIME,
    created_by        BIGINT,
    created_at        DATETIME,
    updated_at        DATETIME
);
CREATE INDEX idx_audit_status ON op_audit (audit_status, biz_type);

CREATE TABLE op_sensitive_word (
    id         BIGINT      NOT NULL PRIMARY KEY,
    word       VARCHAR(50) NOT NULL,
    created_by BIGINT,
    created_at DATETIME,
    updated_at DATETIME,
    CONSTRAINT uk_sensitive_word UNIQUE (word)
);

CREATE TABLE op_feedback (
    id            BIGINT        NOT NULL PRIMARY KEY,
    user_id       BIGINT        NOT NULL,
    feedback_type VARCHAR(30),
    source_app    VARCHAR(20),
    contact       VARCHAR(50)   NOT NULL,
    content       VARCHAR(2000) NOT NULL,
    images        VARCHAR(1000),
    remark        VARCHAR(500),
    fb_status     VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    reply         VARCHAR(2000),
    replied_by    BIGINT,
    replied_at    DATETIME,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);
CREATE INDEX idx_feedback_user ON op_feedback (user_id);

CREATE TABLE op_report (
    id            BIGINT       NOT NULL PRIMARY KEY,
    reporter_id   BIGINT       NOT NULL,
    target_type   VARCHAR(20)  NOT NULL,
    target_id     BIGINT       NOT NULL,
    target_owner_id BIGINT,
    reason        VARCHAR(500) NOT NULL,
    report_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    result        VARCHAR(500),
    handled_by    BIGINT,
    handled_at    DATETIME,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);
CREATE INDEX idx_report_status ON op_report (report_status);

CREATE TABLE cms_content (
    id             BIGINT       NOT NULL PRIMARY KEY,
    content_type   VARCHAR(20)  NOT NULL,
    title          VARCHAR(200) NOT NULL,
    cover          VARCHAR(255),
    link           VARCHAR(255),
    body           TEXT,
    content_status VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    sort_no        INT          NOT NULL DEFAULT 0,
    publish_at     DATETIME,
    view_count     BIGINT       NOT NULL DEFAULT 0,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME
);
CREATE INDEX idx_cms_type ON cms_content (content_type, content_status);

INSERT INTO op_sensitive_word (id, word) VALUES (1, '赌博'), (2, '代考'), (3, '枪支'), (4, '毒品'), (5, '色情');

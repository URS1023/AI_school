-- 学情：分档设置（D3，学校可自定义）、错题、错题本、讲评重点题

CREATE TABLE ln_band_setting (
    id             BIGINT       NOT NULL PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL,
    subject        VARCHAR(30),
    excellent_rate DECIMAL(5, 2) NOT NULL DEFAULT 85,
    good_rate      DECIMAL(5, 2) NOT NULL DEFAULT 75,
    pass_rate      DECIMAL(5, 2) NOT NULL DEFAULT 60,
    critical_range DECIMAL(5, 2) NOT NULL DEFAULT 5,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME
);

CREATE TABLE ln_wrong_item (
    id             BIGINT        NOT NULL PRIMARY KEY,
    tenant_id      BIGINT        NOT NULL,
    student_id     BIGINT        NOT NULL,
    class_id       BIGINT,
    source_type    VARCHAR(10)   NOT NULL,
    source_id      BIGINT        NOT NULL,
    source_title   VARCHAR(200),
    subject        VARCHAR(30),
    item_no        VARCHAR(20)   NOT NULL,
    item_type      VARCHAR(30),
    question_id    BIGINT,
    score          DECIMAL(6, 2),
    full_score     DECIMAL(6, 2) NOT NULL,
    student_answer VARCHAR(500),
    mastered       TINYINT       NOT NULL DEFAULT 0,
    mastered_at    DATETIME,
    occurred_at    DATETIME,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME,
    CONSTRAINT uk_wrong_item UNIQUE (source_type, source_id, student_id, item_no)
);
CREATE INDEX idx_wrong_student ON ln_wrong_item (student_id, mastered);
CREATE INDEX idx_wrong_class ON ln_wrong_item (class_id, subject);

-- 错题本：只有一个「是否制卡」字段（修复缺陷 23 表头重复）
CREATE TABLE ln_wrong_book (
    id            BIGINT       NOT NULL PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL,
    owner_user_id BIGINT       NOT NULL,
    book_name     VARCHAR(200) NOT NULL,
    subject       VARCHAR(30),
    student_id    BIGINT,
    wrong_ids     TEXT         NOT NULL,
    sheet_made    TINYINT      NOT NULL DEFAULT 0,
    sheet_id      BIGINT,
    created_by    BIGINT,
    created_at    DATETIME,
    updated_at    DATETIME
);

CREATE TABLE ln_key_question (
    id          BIGINT      NOT NULL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    source_type VARCHAR(10) NOT NULL,
    source_id   BIGINT      NOT NULL,
    item_no     VARCHAR(20) NOT NULL,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_key_question UNIQUE (source_type, source_id, item_no)
);

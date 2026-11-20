-- 考试阅卷：答题卡与版本、作业、考试、考生、扫描批次、答卷与逐题得分、改分记录、阅卷分配、问题卷、分批发布

CREATE TABLE ex_sheet (
    id                BIGINT       NOT NULL PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL,
    owner_user_id     BIGINT       NOT NULL,
    sheet_name        VARCHAR(100) NOT NULL,
    subject           VARCHAR(30),
    paper_id          BIGINT,
    paper_size        VARCHAR(10)  NOT NULL DEFAULT 'A4',
    sheet_style       VARCHAR(20)  NOT NULL DEFAULT 'CARD_ONLY',
    mark_mode         VARCHAR(20)  NOT NULL DEFAULT 'SCAN_THEN_MARK',
    student_no_digits INT          NOT NULL DEFAULT 6,
    no_style          VARCHAR(20)  NOT NULL DEFAULT 'HANDWRITE',
    current_version   INT          NOT NULL DEFAULT 1,
    created_by        BIGINT,
    created_at        DATETIME,
    updated_at        DATETIME
);

-- 版本：下载 / 打印即冻结（frozen），有扫描即锁定（locked）。二维码 = 答题卡 ID + 版本号
CREATE TABLE ex_sheet_version (
    id          BIGINT        NOT NULL PRIMARY KEY,
    tenant_id   BIGINT        NOT NULL,
    sheet_id    BIGINT        NOT NULL,
    version_no  INT           NOT NULL,
    layout_json TEXT          NOT NULL,
    total_score DECIMAL(8, 2) NOT NULL,
    frozen      TINYINT       NOT NULL DEFAULT 0,
    locked      TINYINT       NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME,
    CONSTRAINT uk_sheet_version UNIQUE (sheet_id, version_no)
);

CREATE TABLE ex_homework (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL,
    year_id            BIGINT       NOT NULL,
    title              VARCHAR(200) NOT NULL,
    hw_mode            VARCHAR(10)  NOT NULL,
    scan_type          VARCHAR(20)  NOT NULL DEFAULT 'ANSWER_SHEET',
    subject            VARCHAR(30),
    paper_id           BIGINT,
    sheet_version_id   BIGINT,
    class_ids          VARCHAR(500) NOT NULL,
    student_ids        TEXT,
    deadline           DATETIME,
    allow_late         TINYINT      NOT NULL DEFAULT 1,
    allow_withdraw     TINYINT      NOT NULL DEFAULT 1,
    assign_to_teacher  TINYINT      NOT NULL DEFAULT 0,
    answer_published   TINYINT      NOT NULL DEFAULT 0,
    hw_status          VARCHAR(20)  NOT NULL,
    creator_identity_id BIGINT      NOT NULL,
    marked_at          DATETIME,
    created_by         BIGINT,
    created_at         DATETIME,
    updated_at         DATETIME
);
CREATE INDEX idx_hw_tenant ON ex_homework (tenant_id, year_id, hw_status);

CREATE TABLE ex_exam (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL,
    year_id              BIGINT       NOT NULL,
    exam_name            VARCHAR(200) NOT NULL,
    exam_type            VARCHAR(20)  NOT NULL DEFAULT 'SCHOOL',
    grade_id             BIGINT,
    subject              VARCHAR(30)  NOT NULL,
    paper_id             BIGINT,
    sheet_version_id     BIGINT,
    exam_time            DATETIME,
    exam_status          VARCHAR(30)  NOT NULL,
    score_audit_required TINYINT      NOT NULL DEFAULT 1,
    show_rank            TINYINT      NOT NULL DEFAULT 0,
    view_settings        VARCHAR(500),
    creator_identity_id  BIGINT       NOT NULL,
    created_by           BIGINT,
    created_at           DATETIME,
    updated_at           DATETIME
);
CREATE INDEX idx_exam_tenant ON ex_exam (tenant_id, year_id, exam_status);

CREATE TABLE ex_examinee (
    id               BIGINT      NOT NULL PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL,
    exam_id          BIGINT      NOT NULL,
    student_id       BIGINT      NOT NULL,
    class_id         BIGINT      NOT NULL,
    examinee_status  VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    created_by       BIGINT,
    created_at       DATETIME,
    updated_at       DATETIME,
    CONSTRAINT uk_examinee UNIQUE (exam_id, student_id)
);

-- 分批发布：按班级发布 / 撤回（PRD 11.5-C）
CREATE TABLE ex_publish (
    id           BIGINT      NOT NULL PRIMARY KEY,
    tenant_id    BIGINT      NOT NULL,
    exam_id      BIGINT      NOT NULL,
    class_id     BIGINT      NOT NULL,
    publish_kind VARCHAR(20) NOT NULL DEFAULT 'SCORE',
    published_at DATETIME    NOT NULL,
    withdrawn_at DATETIME,
    created_by   BIGINT,
    created_at   DATETIME,
    updated_at   DATETIME
);
CREATE INDEX idx_publish_exam ON ex_publish (exam_id, class_id);

CREATE TABLE ex_scan_batch (
    id               BIGINT      NOT NULL PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL,
    source_type      VARCHAR(10) NOT NULL,
    source_id        BIGINT      NOT NULL,
    scan_type        VARCHAR(20) NOT NULL,
    image_count      INT         NOT NULL DEFAULT 0,
    recognized_count INT         NOT NULL DEFAULT 0,
    exception_count  INT         NOT NULL DEFAULT 0,
    batch_status     VARCHAR(20) NOT NULL DEFAULT 'SCANNING',
    scanned_by       BIGINT,
    created_by       BIGINT,
    created_at       DATETIME,
    updated_at       DATETIME
);

CREATE TABLE ex_answer (
    id                BIGINT        NOT NULL PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL,
    source_type       VARCHAR(10)   NOT NULL,
    source_id         BIGINT        NOT NULL,
    student_id        BIGINT,
    class_id          BIGINT,
    sheet_version_id  BIGINT,
    batch_id          BIGINT,
    page_ref          VARCHAR(50),
    images            VARCHAR(1000),
    raw_student_no    VARCHAR(30),
    answer_status     VARCHAR(20)   NOT NULL,
    exception_reason  VARCHAR(30),
    is_current        TINYINT       NOT NULL DEFAULT 1,
    replaced_by       BIGINT,
    late_flag         TINYINT       NOT NULL DEFAULT 0,
    makeup_flag       TINYINT       NOT NULL DEFAULT 0,
    total_score       DECIMAL(8, 2),
    objective_score   DECIMAL(8, 2),
    subjective_score  DECIMAL(8, 2),
    full_score        DECIMAL(8, 2),
    submitted_at      DATETIME,
    created_by        BIGINT,
    created_at        DATETIME,
    updated_at        DATETIME
);
CREATE INDEX idx_answer_source ON ex_answer (source_type, source_id, is_current);
CREATE INDEX idx_answer_student ON ex_answer (student_id, source_type);

CREATE TABLE ex_answer_item (
    id             BIGINT        NOT NULL PRIMARY KEY,
    tenant_id      BIGINT        NOT NULL,
    answer_id      BIGINT        NOT NULL,
    item_no        VARCHAR(20)   NOT NULL,
    item_type      VARCHAR(30)   NOT NULL,
    objective      TINYINT       NOT NULL,
    full_score     DECIMAL(6, 2) NOT NULL,
    std_answer     VARCHAR(500),
    student_answer TEXT,
    score          DECIMAL(6, 2),
    score_source   VARCHAR(20),
    needs_review   TINYINT       NOT NULL DEFAULT 0,
    review_reason  VARCHAR(100),
    ai_comment     TEXT,
    marked_by      BIGINT,
    marked_at      DATETIME,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME,
    CONSTRAINT uk_answer_item UNIQUE (answer_id, item_no)
);

-- 改分记录（发布前 / 发布后），发布后改分要通知学生和家长（AC-05）
CREATE TABLE ex_score_change (
    id           BIGINT        NOT NULL PRIMARY KEY,
    tenant_id    BIGINT        NOT NULL,
    answer_id    BIGINT        NOT NULL,
    student_id   BIGINT,
    item_no      VARCHAR(20),
    old_score    DECIMAL(8, 2),
    new_score    DECIMAL(8, 2),
    old_total    DECIMAL(8, 2),
    new_total    DECIMAL(8, 2),
    reason       VARCHAR(300),
    change_kind  VARCHAR(30)   NOT NULL,
    after_publish TINYINT      NOT NULL DEFAULT 0,
    notified     TINYINT       NOT NULL DEFAULT 0,
    operator_id  BIGINT,
    created_by   BIGINT,
    created_at   DATETIME,
    updated_at   DATETIME
);
CREATE INDEX idx_score_change ON ex_score_change (answer_id);

CREATE TABLE ex_marking_assign (
    id          BIGINT      NOT NULL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    source_type VARCHAR(10) NOT NULL,
    source_id   BIGINT      NOT NULL,
    item_no     VARCHAR(20) NOT NULL,
    identity_id BIGINT      NOT NULL,
    created_by  BIGINT,
    created_at  DATETIME,
    updated_at  DATETIME
);

CREATE TABLE ex_problem_paper (
    id             BIGINT       NOT NULL PRIMARY KEY,
    tenant_id      BIGINT       NOT NULL,
    answer_id      BIGINT       NOT NULL,
    item_no        VARCHAR(20),
    problem_type   VARCHAR(30)  NOT NULL,
    remark         VARCHAR(300),
    problem_status VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    reporter_id    BIGINT,
    handled_by     BIGINT,
    handled_at     DATETIME,
    created_by     BIGINT,
    created_at     DATETIME,
    updated_at     DATETIME
);
